package com.seastella.servicerequest.internal;

import com.seastella.core.api.error.ForbiddenException;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
import com.seastella.fleet.api.FleetDirectory;
import com.seastella.fleet.api.SpareServiceHistory;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeKind;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.identity.api.UserDirectory;
import com.seastella.maintenance.api.MaintenanceCycle;
import com.seastella.servicerequest.api.Priority;
import com.seastella.servicerequest.api.ServiceRequestAction;
import com.seastella.servicerequest.api.ServiceRequestCommands;
import com.seastella.servicerequest.api.TroubleshootingGate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The write side of the service request lifecycle.
 *
 * <p>This class owns creation and the record-backed transitions (the
 * completion report). Every status change is still made by
 * {@link ServiceRequestStateMachine}, which re-checks scope, role, source state,
 * engineer identity, the invoice gate and required reasons — nothing here
 * bypasses those guards, it only prepares what they need.
 */
@Service
class DefaultServiceRequestCommands implements ServiceRequestCommands {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final ServiceRequestRepository requests;
    private final CompletionReportRepository reports;
    private final ServiceRequestStateMachine stateMachine;
    private final ScopeResolver scopeResolver;
    private final FleetDirectory fleet;
    private final UserDirectory users;
    private final MaintenanceCycle maintenance;
    private final SpareServiceHistory serviceHistory;
    private final ObjectProvider<TroubleshootingGate> troubleshootingGate;

    DefaultServiceRequestCommands(ServiceRequestRepository requests,
                                  CompletionReportRepository reports,
                                  ServiceRequestStateMachine stateMachine,
                                  ScopeResolver scopeResolver,
                                  FleetDirectory fleet,
                                  UserDirectory users,
                                  MaintenanceCycle maintenance,
                                  SpareServiceHistory serviceHistory,
                                  ObjectProvider<TroubleshootingGate> troubleshootingGate) {
        this.serviceHistory = serviceHistory;
        this.requests = requests;
        this.reports = reports;
        this.stateMachine = stateMachine;
        this.scopeResolver = scopeResolver;
        this.fleet = fleet;
        this.users = users;
        this.maintenance = maintenance;
        this.troubleshootingGate = troubleshootingGate;
    }

    @Override
    @Transactional
    public Long raise(Raise command) {
        AccessScope scope = scopeResolver.currentScope();
        if (scope.role() != Role.CAPTAIN) {
            // RBAC matrix: raising a request belongs to the Captain alone.
            throw ForbiddenException.ofAction("raise a service request");
        }

        if (command.spareId() == null) {
            throw new ValidationException("Choose the spare the problem is on.");
        }
        String title = required(command.title(), "Describe the problem in a short title.", 200);
        String description = required(command.description(), "Add a description of what is happening.", 4000);

        Long vesselId = fleet.vesselIdForSpare(command.spareId());
        if (vesselId == null || !scope.permitsVessel(vesselId)) {
            // Not found, not forbidden: an id outside scope must not confirm it exists.
            throw NotFoundException.ofResource("Spare", command.spareId());
        }
        Long organizationId = fleet.organizationIdForVessel(vesselId);
        String orgCode = fleet.organizationCodeForVessel(vesselId).orElse("ORG");

        ServiceRequest request = new ServiceRequest(
                nextRequestNumber(orgCode), organizationId, vesselId, command.spareId(),
                scope.userId(), title, description,
                command.priority() == null ? Priority.MEDIUM : command.priority());
        requests.save(request);

        stateMachine.recordCreation(request, scope.userId(), Role.CAPTAIN);
        return request.getId();
    }

    @Override
    @Transactional
    public void transition(Long serviceRequestId, ServiceRequestAction action, String reason, Long engineerUserId) {
        AccessScope scope = scopeResolver.currentScope();

        if (action == ServiceRequestAction.ASSIGN_ENGINEER) {
            // The state machine checks that someone was chosen; this checks
            // that the someone really is an active Service Engineer.
            boolean isEngineer = users.find(engineerUserId)
                    .filter(u -> u.role() == Role.SERVICE_ENGINEER && u.active())
                    .isPresent();
            if (!isEngineer) {
                throw new ValidationException("Choose an active Service Engineer to assign.");
            }
        }

        if (action == ServiceRequestAction.SUBMIT_FOR_APPROVAL || action == ServiceRequestAction.ESCALATE_TO_LIVE_AGENT) {
            // SoW s6.1: the guided checks come first.
            TroubleshootingGate gate = troubleshootingGate.getIfAvailable();
            String blocked = gate == null ? null : gate.blockingReason(serviceRequestId);
            if (blocked != null) {
                throw new WorkflowException(blocked);
            }
        }

        ServiceRequest request = stateMachine.apply(serviceRequestId,
                new TransitionRequest(action, scope.userId(), scope.role(), trimToNull(reason), engineerUserId));

        if (action == ServiceRequestAction.COMPLETE) {
            // SoW s18: a completed service recalculates the next-service-due
            // date. Same transaction, so the new cycle commits with the completion.
            LocalDate serviceDate = reports.findByServiceRequestId(request.getId())
                    .map(CompletionReport::getServiceDate)
                    .orElse(LocalDate.now(ZoneOffset.UTC));
            maintenance.serviceCompleted(request.getSpareId(), serviceDate);
            // SRQ-19: and the spare itself carries the date of its last service,
            // which is what the equipment report and a surveyor ask for.
            serviceHistory.recordCompletedService(request.getSpareId(), serviceDate, request.getRequestNumber());
        }
    }

    @Override
    @Transactional
    public void submitCompletion(Long serviceRequestId, Completion report) {
        AccessScope scope = scopeResolver.currentScope();
        String workPerformed = required(report.workPerformed(), "Describe the work performed.", 4000);
        String outcome = required(report.outcome(), "State the outcome of the service.", 400);

        ServiceRequest request = loadInScope(serviceRequestId, scope);
        if (reports.findByServiceRequestId(serviceRequestId).isPresent()) {
            throw new WorkflowException("A completion report has already been submitted for "
                    + request.getRequestNumber() + ".");
        }

        // Transition first: it confirms this is the assigned engineer and the
        // job is in progress. The report is saved only if that succeeds, in the
        // same transaction.
        stateMachine.applyTo(request, TransitionRequest.of(
                ServiceRequestAction.SUBMIT_COMPLETION, scope.userId(), scope.role()));

        CompletionReport entity = new CompletionReport(serviceRequestId, request.getVesselId(),
                scope.userId(), workPerformed, outcome,
                report.serviceDate() == null ? LocalDate.now(ZoneOffset.UTC) : report.serviceDate());
        entity.setPartsUsed(trimToNull(report.partsUsed()));
        reports.save(entity);
    }

    @Override
    @Transactional
    public void recordProblemType(Long serviceRequestId, Long problemTypeId) {
        ServiceRequest request = loadInScope(serviceRequestId, scopeResolver.currentScope());
        request.setProblemTypeId(problemTypeId);
        requests.save(request);
    }

    @Override
    @Transactional(readOnly = true)
    public Placement placementInScope(Long serviceRequestId) {
        ServiceRequest request = loadInScope(serviceRequestId, scopeResolver.currentScope());
        return new Placement(request.getId(), request.getRequestNumber(),
                request.getOrganizationId(), request.getVesselId());
    }

    /** Loads a request the caller may see; anything else is indistinguishable from absent. */
    ServiceRequest loadInScope(Long serviceRequestId, AccessScope scope) {
        ServiceRequest request = requests.findById(serviceRequestId)
                .orElseThrow(() -> NotFoundException.ofResource("ServiceRequest", serviceRequestId));

        boolean visible = scope.kind() == ScopeKind.JOB_SET
                ? scope.assignedJobIds().contains(request.getId())
                : scope.permitsVessel(request.getVesselId());
        if (!visible) {
            throw NotFoundException.ofResource("ServiceRequest", serviceRequestId);
        }
        return request;
    }

    /** SR-ACME-202609-0017: organization, month raised, running number within that month. */
    private String nextRequestNumber(String orgCode) {
        String prefix = "SR-" + orgCode + "-" + LocalDate.now(ZoneOffset.UTC).format(MONTH) + "-";
        long seq = requests.countByRequestNumberStartingWith(prefix) + 1;
        String number = prefix + String.format("%04d", seq);
        while (requests.existsByRequestNumber(number)) {
            seq++;
            number = prefix + String.format("%04d", seq);
        }
        return number;
    }

    private static String required(String value, String message, int max) {
        String trimmed = trimToNull(value);
        if (trimmed == null) throw new ValidationException(message);
        if (trimmed.length() > max) {
            throw new ValidationException("Keep this under " + max + " characters.");
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
