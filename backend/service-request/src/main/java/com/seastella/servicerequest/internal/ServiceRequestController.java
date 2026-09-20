package com.seastella.servicerequest.internal;

import com.seastella.core.api.error.ValidationException;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeKind;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.servicerequest.api.InvoiceGateQuery;
import com.seastella.servicerequest.api.InvoiceLookup;
import com.seastella.servicerequest.api.Priority;
import com.seastella.servicerequest.api.ServiceRequestAction;
import com.seastella.servicerequest.api.ServiceRequestCommands;
import com.seastella.servicerequest.api.ServiceRequestMetrics;
import com.seastella.servicerequest.api.ServiceRequestMetrics.ActivityItem;
import com.seastella.servicerequest.api.ServiceRequestMetrics.CompletionSummary;
import com.seastella.servicerequest.api.ServiceRequestMetrics.RequestSummary;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import com.seastella.servicerequest.api.TroubleshootingGate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Service requests: list, detail, raise, and every step of the lifecycle.
 *
 * <p>The detail response tells the client which actions the signed-in user may
 * take next, and in what form. That list is derived from the same workflow
 * table the state machine enforces, so a button the UI shows is one the server
 * will accept — and the server still re-checks everything when it is pressed.
 */
@RestController
@RequestMapping("/api/v1/service-requests")
class ServiceRequestController {

    /** Roles allowed to see invoice amounts (RBAC matrix, SOURCE-A §12). */
    private static final Set<Role> FINANCIAL = EnumSet.of(
            Role.PLATFORM_ADMIN, Role.TECHNICAL_HEAD, Role.SHIP_MANAGER, Role.SERVICE_COORDINATOR);

    /** Actions that create or decide a record, and so have their own endpoints. */
    private static final Set<ServiceRequestAction> RECORD_BACKED = EnumSet.of(
            ServiceRequestAction.RAISE, ServiceRequestAction.START_TROUBLESHOOTING,
            ServiceRequestAction.RAISE_INVOICE,
            ServiceRequestAction.ACCEPT_INVOICE, ServiceRequestAction.REJECT_INVOICE,
            ServiceRequestAction.QUERY_INVOICE, ServiceRequestAction.SUBMIT_COMPLETION);

    private static final int LIST_LIMIT = 200;

    private final DefaultServiceRequestCommands commands;
    private final ServiceRequestMetrics metrics;
    private final InvoiceLookup invoices;
    private final InvoiceGateQuery invoiceGate;
    private final ScopeResolver scopeResolver;
    private final ObjectProvider<TroubleshootingGate> troubleshootingGate;

    ServiceRequestController(DefaultServiceRequestCommands commands, ServiceRequestMetrics metrics,
                             InvoiceLookup invoices, InvoiceGateQuery invoiceGate,
                             ScopeResolver scopeResolver, ObjectProvider<TroubleshootingGate> troubleshootingGate) {
        this.commands = commands;
        this.metrics = metrics;
        this.invoices = invoices;
        this.invoiceGate = invoiceGate;
        this.scopeResolver = scopeResolver;
        this.troubleshootingGate = troubleshootingGate;
    }

    @GetMapping
    @Transactional(readOnly = true)
    ResponseEntity<List<RequestSummary>> list() {
        AccessScope scope = scopeResolver.currentScope();
        List<RequestSummary> rows = switch (scope.kind()) {
            case PLATFORM -> metrics.recentPlatformWide(LIST_LIMIT);
            case JOB_SET -> metrics.forEngineer(scope.userId(), EnumSet.allOf(ServiceRequestStatus.class));
            default -> metrics.recent(scope.vesselIds(), LIST_LIMIT);
        };
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    ResponseEntity<Detail> detail(@PathVariable Long id) {
        return ResponseEntity.ok(buildDetail(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('CAPTAIN')")
    ResponseEntity<Detail> raise(@RequestBody RaiseBody body) {
        Long id = commands.raise(new ServiceRequestCommands.Raise(
                body.spareId(), body.title(), body.description(), parsePriority(body.priority())));
        return ResponseEntity.status(HttpStatus.CREATED).body(buildDetail(id));
    }

    @PostMapping("/{id}/actions")
    ResponseEntity<Detail> act(@PathVariable Long id, @RequestBody ActionBody body) {
        ServiceRequestAction action = parseAction(body.action());
        if (RECORD_BACKED.contains(action)) {
            throw new ValidationException(action.label() + " has its own form.");
        }
        commands.transition(id, action, body.reason(), body.engineerUserId());
        return ResponseEntity.ok(buildDetail(id));
    }

    @PostMapping("/{id}/completion-report")
    @PreAuthorize("hasRole('SERVICE_ENGINEER')")
    ResponseEntity<Detail> completionReport(@PathVariable Long id, @RequestBody CompletionBody body) {
        commands.submitCompletion(id, new ServiceRequestCommands.Completion(
                body.workPerformed(), body.partsUsed(), body.outcome(), body.serviceDate()));
        return ResponseEntity.ok(buildDetail(id));
    }

    // ------------------------------------------------------------------ detail

    private Detail buildDetail(Long id) {
        AccessScope scope = scopeResolver.currentScope();
        ServiceRequest entity = commands.loadInScope(id, scope);
        RequestSummary summary = metrics.summary(id)
                .orElseThrow(() -> com.seastella.core.api.error.NotFoundException.ofResource("ServiceRequest", id));

        boolean financial = FINANCIAL.contains(scope.role());
        List<ActivityItem> history = metrics.history(id);
        CompletionSummary report = metrics.completionReport(id);

        return new Detail(
                summary,
                entity.getDescription(),
                entity.getLastReason(),
                entity.getResolutionType().name(),
                entity.getStatus().isTerminal(),
                history,
                report == null ? null : new CompletionView(
                        report.engineerName(), report.workPerformed(), report.partsUsed(), report.outcome(),
                        report.reportedAt(), report.relayNote(),
                        financial ? report.finalCost() : null),
                financial ? invoices.forRequest(id) : List.of(),
                financial,
                availableActions(entity, scope));
    }

    private List<ActionOption> availableActions(ServiceRequest entity, AccessScope scope) {
        if (scope.role() == null || entity.getStatus().isTerminal()) return List.of();

        return ServiceRequestWorkflow.availableActions(entity.getStatus(), scope.role()).stream()
                .filter(a -> a != ServiceRequestAction.RAISE)
                // Guided checks are started from the troubleshooting panel, not a button.
                .filter(a -> a != ServiceRequestAction.START_TROUBLESHOOTING)
                .filter(a -> !((a == ServiceRequestAction.SUBMIT_FOR_APPROVAL || a == ServiceRequestAction.ESCALATE_TO_LIVE_AGENT)
                        && troubleshootingBlocks(entity.getId())))
                // Engineer steps belong to the engineer on the job, not every engineer.
                .filter(a -> !(a == ServiceRequestAction.START_WORK || a == ServiceRequestAction.SUBMIT_COMPLETION)
                        || (scope.kind() == ScopeKind.JOB_SET
                            && scope.userId().equals(entity.getAssignedEngineerUserId())))
                // The gate, shown as the server enforces it.
                .filter(a -> a != ServiceRequestAction.ASSIGN_ENGINEER || invoiceGate.hasAcceptedInvoice(entity.getId()))
                .map(a -> new ActionOption(a.name(), a.label(), formFor(a), a.requiresReason()))
                .toList();
    }

    private boolean troubleshootingBlocks(Long requestId) {
        TroubleshootingGate gate = troubleshootingGate.getIfAvailable();
        return gate != null && gate.blockingReason(requestId) != null;
    }

    private static String formFor(ServiceRequestAction a) {
        return switch (a) {
            case RAISE_INVOICE -> "INVOICE";
            case ACCEPT_INVOICE, REJECT_INVOICE, QUERY_INVOICE -> "INVOICE_DECISION";
            case ASSIGN_ENGINEER -> "ASSIGN_ENGINEER";
            case SUBMIT_COMPLETION -> "COMPLETION_REPORT";
            default -> a.requiresReason() ? "REASON" : "CONFIRM";
        };
    }

    private static ServiceRequestAction parseAction(String raw) {
        try {
            return ServiceRequestAction.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new ValidationException("Unknown action.");
        }
    }

    private static Priority parsePriority(String raw) {
        if (raw == null || raw.isBlank()) return Priority.MEDIUM;
        try {
            return Priority.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new ValidationException("Priority must be Critical, High, Medium or Low.");
        }
    }

    // ------------------------------------------------------------------ bodies

    record RaiseBody(Long spareId, String title, String description, String priority) {}

    record ActionBody(String action, String reason, Long engineerUserId) {}

    record CompletionBody(String workPerformed, String partsUsed, String outcome, LocalDate serviceDate) {}

    record Detail(
            RequestSummary request,
            String description,
            String lastReason,
            String resolutionType,
            boolean closed,
            List<ActivityItem> history,
            CompletionView completion,
            List<InvoiceLookup.InvoiceView> invoices,
            boolean financialsVisible,
            List<ActionOption> actions) {}

    record CompletionView(String engineerName, String workPerformed, String partsUsed, String outcome,
                          Instant reportedAt, String relayNote, String finalCost) {}

    /** `form` tells the client what to collect before sending the action. */
    record ActionOption(String action, String label, String form, boolean requiresReason) {}
}
