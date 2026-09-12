package com.seastella.servicerequest.internal;

import com.seastella.core.api.error.ForbiddenException;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
import com.seastella.core.api.event.DomainEventPublisher;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.servicerequest.api.InvoiceGateQuery;
import com.seastella.servicerequest.api.ServiceRequestAction;
import com.seastella.servicerequest.api.ServiceRequestEvents;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Applies transitions. <b>The only path by which a request's status changes.</b>
 *
 * <p>Every attempt runs the same seven checks, in this order, and each one
 * throws rather than returning a boolean, so a caller cannot ignore a failure:
 *
 * <ol>
 *   <li><b>Known action</b> - the action exists in the transition table.</li>
 *   <li><b>Scope</b> - the actor may see this request's vessel at all. Checked
 *       first among the authorization checks, and reported as 404, so an actor
 *       outside the vessel cannot distinguish "not yours" from "does not
 *       exist".</li>
 *   <li><b>Role</b> - the actor's role appears in the edge's allowed set.</li>
 *   <li><b>Source state</b> - the request's current status is a legal source
 *       for this edge. This is where stage-skipping is refused.</li>
 *   <li><b>Actor identity</b> - for engineer actions, the actor is <em>this
 *       request's</em> assigned engineer (G7).</li>
 *   <li><b>Invoice gate</b> - for assignment, an accepted invoice exists (G1).</li>
 *   <li><b>Reason</b> - rejections and queries carry the text the SoW requires.</li>
 * </ol>
 *
 * <p>Only then is the aggregate mutated, the transition logged, and the event
 * published - all inside the caller's transaction, so a rollback takes the log
 * and the status with it.
 */
@Component
public class ServiceRequestStateMachine {

    private final ServiceRequestRepository requests;
    private final ServiceRequestTransitionLogRepository transitionLog;
    private final ScopeResolver scopeResolver;
    private final InvoiceGateQuery invoiceGate;
    private final DomainEventPublisher events;

    ServiceRequestStateMachine(ServiceRequestRepository requests,
                               ServiceRequestTransitionLogRepository transitionLog,
                               ScopeResolver scopeResolver,
                               InvoiceGateQuery invoiceGate,
                               DomainEventPublisher events) {
        this.requests = requests;
        this.transitionLog = transitionLog;
        this.scopeResolver = scopeResolver;
        this.invoiceGate = invoiceGate;
        this.events = events;
    }

    /**
     * Applies one transition to one request.
     *
     * @throws NotFoundException   the request does not exist, or is out of scope
     * @throws ForbiddenException  the role may not fire this action
     * @throws WorkflowException   the current state does not permit it, or a
     *                             guard (notably the invoice gate) refused
     * @throws ValidationException a required reason was missing
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public ServiceRequest apply(Long serviceRequestId, TransitionRequest request) {
        ServiceRequest entity = requests.findById(serviceRequestId)
                .orElseThrow(() -> NotFoundException.ofResource("ServiceRequest", serviceRequestId));

        return applyTo(entity, request);
    }

    /** As {@link #apply}, for a request already loaded and scope-checked. */
    @Transactional(propagation = Propagation.REQUIRED)
    public ServiceRequest applyTo(ServiceRequest entity, TransitionRequest request) {

        // 1. Known action.
        TransitionDefinition definition = ServiceRequestWorkflow.find(request.action())
                .orElseThrow(() -> new WorkflowException(
                        "Unknown action: " + request.action()));

        // 2. Scope. 404 rather than 403 - see SEC-08.
        assertInScope(entity);

        // 3. Role.
        if (!definition.permitsRole(request.actorRole())) {
            throw ForbiddenException.ofAction(
                    request.actorRole() + " may not " + definition.action().label().toLowerCase());
        }

        // 4. Source state. Stage-skipping is refused here.
        ServiceRequestStatus current = entity.getStatus();
        if (current.isTerminal()) {
            throw new WorkflowException(
                    "Request " + entity.getRequestNumber() + " is " + current.label()
                            + " and cannot be changed further.");
        }
        if (!definition.permitsFrom(current)) {
            throw new WorkflowException(
                    definition.action().label() + " is not permitted while the request is "
                            + current.label() + ".");
        }

        // 5. Actor identity, for engineer actions (G7).
        if (definition.requiresActorIsAssignedEngineer()) {
            Long assigned = entity.getAssignedEngineerUserId();
            if (assigned == null || !assigned.equals(request.actorUserId())) {
                // Not this engineer's job: indistinguishable from "no such job".
                throw NotFoundException.ofResource("ServiceRequest", entity.getId());
            }
        }

        // 6. THE INVOICE GATE (G1). Checked against the invoice records, not
        //    only against this request's status column.
        if (definition.requiresAcceptedInvoice() && !invoiceGate.hasAcceptedInvoice(entity.getId())) {
            throw new WorkflowException(
                    "A Service Engineer cannot be assigned until the Ship Manager has "
                            + "accepted an invoice for this request.");
        }

        // 6b. Assignment needs a target engineer.
        if (definition.action() == ServiceRequestAction.ASSIGN_ENGINEER
                && request.engineerUserId() == null) {
            throw new ValidationException("An engineer must be selected for assignment.");
        }

        // 7. Reason, where the workflow requires one.
        if (definition.action().requiresReason()
                && (request.reason() == null || request.reason().isBlank())) {
            throw new ValidationException(
                    definition.action().label() + " requires a reason.");
        }

        // --- all guards passed ---
        Instant now = Instant.now();
        ServiceRequestStatus from = current;
        ServiceRequestStatus to = definition.toState();

        entity.applyTransition(to, request, now);
        requests.save(entity);

        transitionLog.save(new ServiceRequestTransitionLog(
                entity.getId(), entity.getVesselId(), from, to, definition.action(),
                request.actorUserId(), roleName(request.actorRole()), request.reason(), now));

        events.publish(new ServiceRequestEvents.Transitioned(
                entity.getId(), entity.getRequestNumber(), from, to, definition.action(),
                request.actorUserId(), roleName(request.actorRole()), request.reason(),
                entity.getOrganizationId(), entity.getVesselId(), entity.getSpareId(), now));

        return entity;
    }

    /** Records the creation edge, which has no source state. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordCreation(ServiceRequest entity, Long actorUserId, Role actorRole) {
        Instant now = Instant.now();

        transitionLog.save(new ServiceRequestTransitionLog(
                entity.getId(), entity.getVesselId(), null, ServiceRequestStatus.REPORTED,
                ServiceRequestAction.RAISE, actorUserId, roleName(actorRole), null, now));

        events.publish(new ServiceRequestEvents.Transitioned(
                entity.getId(), entity.getRequestNumber(), null, ServiceRequestStatus.REPORTED,
                ServiceRequestAction.RAISE, actorUserId, roleName(actorRole), null,
                entity.getOrganizationId(), entity.getVesselId(), entity.getSpareId(), now));
    }

    private void assertInScope(ServiceRequest entity) {
        AccessScope scope = scopeResolver.currentScope();

        // An engineer's reach is their job set, never a vessel set.
        if (scope.kind() == com.seastella.identity.api.ScopeKind.JOB_SET) {
            if (!scope.assignedJobIds().contains(entity.getId())) {
                throw NotFoundException.ofResource("ServiceRequest", entity.getId());
            }
            return;
        }
        if (!scope.permitsVessel(entity.getVesselId())) {
            throw NotFoundException.ofResource("ServiceRequest", entity.getId());
        }
    }

    private static String roleName(Role role) {
        return Optional.ofNullable(role).map(Enum::name).orElse(null);
    }
}
