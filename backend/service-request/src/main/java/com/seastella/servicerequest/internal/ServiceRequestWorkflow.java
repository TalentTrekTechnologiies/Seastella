package com.seastella.servicerequest.internal;

import com.seastella.identity.api.Role;
import com.seastella.servicerequest.api.ServiceRequestAction;
import com.seastella.servicerequest.api.ServiceRequestStatus;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.seastella.identity.api.Role.CAPTAIN;
import static com.seastella.identity.api.Role.SERVICE_COORDINATOR;
import static com.seastella.identity.api.Role.SERVICE_ENGINEER;
import static com.seastella.identity.api.Role.SHIP_MANAGER;
import static com.seastella.servicerequest.api.ServiceRequestAction.*;
import static com.seastella.servicerequest.api.ServiceRequestStatus.*;

/**
 * <b>The transition table.</b> This is the whole workflow, in one readable
 * place, rather than scattered {@code if (status.equals("..."))} checks.
 *
 * <p>Two invariants are structural here rather than defensive:
 *
 * <ol>
 *   <li><b>The invoice gate (G1).</b> {@link ServiceRequestAction#ASSIGN_ENGINEER}
 *       lists exactly one legal source state - {@link ServiceRequestStatus#INVOICE_ACCEPTED}.
 *       {@code INVOICE_RAISED}, {@code INVOICE_QUERIED} and
 *       {@code INVOICE_REJECTED} are simply not in the set, so assignment from
 *       them cannot be expressed. It additionally carries
 *       {@code requiresAcceptedInvoice}, which re-checks the invoice records
 *       themselves in case the status column was moved by something other than
 *       this machine.</li>
 *   <li><b>The reporting chain (G2).</b> No transition reaching
 *       {@link ServiceRequestStatus#COMPLETED} is available to
 *       {@link Role#SERVICE_ENGINEER}. The engineer's furthest reach is
 *       {@code COMPLETION_REPORTED}, which notifies the Coordinator alone. The
 *       Ship Manager learns of completion only when the Coordinator fires
 *       {@link ServiceRequestAction#COMPLETE}.</li>
 * </ol>
 */
final class ServiceRequestWorkflow {

    private static final List<TransitionDefinition> TRANSITIONS = List.of(

            // --- Phase A: request and assisted troubleshooting (SoW s6.1) ---
            TransitionDefinition.of(RAISE,
                    EnumSet.noneOf(ServiceRequestStatus.class), REPORTED, CAPTAIN),

            TransitionDefinition.of(START_TROUBLESHOOTING,
                    EnumSet.of(REPORTED), TROUBLESHOOTING, CAPTAIN),

            TransitionDefinition.of(ESCALATE_TO_LIVE_AGENT,
                    EnumSet.of(TROUBLESHOOTING), LIVE_AGENT_ESCALATED, CAPTAIN),

            TransitionDefinition.of(SUBMIT_FOR_APPROVAL,
                    EnumSet.of(TROUBLESHOOTING, LIVE_AGENT_ESCALATED),
                    PENDING_OPERATIONAL_APPROVAL, CAPTAIN, SERVICE_COORDINATOR),

            // --- Ship Manager operational review (SoW s6.1 step 5) ---
            TransitionDefinition.of(REQUEST_CLARIFICATION,
                    EnumSet.of(PENDING_OPERATIONAL_APPROVAL), CLARIFICATION_REQUESTED, SHIP_MANAGER),

            TransitionDefinition.of(RESUBMIT,
                    EnumSet.of(CLARIFICATION_REQUESTED), PENDING_OPERATIONAL_APPROVAL, CAPTAIN),

            TransitionDefinition.of(REJECT,
                    EnumSet.of(PENDING_OPERATIONAL_APPROVAL), REJECTED, SHIP_MANAGER),

            TransitionDefinition.of(APPROVE_OPERATIONAL,
                    EnumSet.of(PENDING_OPERATIONAL_APPROVAL), OPERATIONALLY_APPROVED, SHIP_MANAGER),

            // --- Phase B: coordinator triage and the invoice gate (SoW s6.2) ---
            TransitionDefinition.of(CLOSE_NO_COST,
                    EnumSet.of(OPERATIONALLY_APPROVED), CLOSED_NO_COST, SERVICE_COORDINATOR),

            // Re-raise after a rejection or query is permitted; OI-08 governs
            // whether that is an edit or a fresh invoice record.
            TransitionDefinition.of(RAISE_INVOICE,
                    EnumSet.of(OPERATIONALLY_APPROVED, INVOICE_REJECTED, INVOICE_QUERIED),
                    INVOICE_RAISED, SERVICE_COORDINATOR),

            TransitionDefinition.of(ACCEPT_INVOICE,
                    EnumSet.of(INVOICE_RAISED), INVOICE_ACCEPTED, SHIP_MANAGER),

            TransitionDefinition.of(REJECT_INVOICE,
                    EnumSet.of(INVOICE_RAISED), INVOICE_REJECTED, SHIP_MANAGER),

            TransitionDefinition.of(QUERY_INVOICE,
                    EnumSet.of(INVOICE_RAISED), INVOICE_QUERIED, SHIP_MANAGER),

            // THE GATE. One legal source state, plus an independent re-check.
            TransitionDefinition.of(ASSIGN_ENGINEER,
                    EnumSet.of(INVOICE_ACCEPTED), ENGINEER_ASSIGNED, SERVICE_COORDINATOR)
                    .requiringAcceptedInvoice(),

            // --- Phase C: execution and completion (SoW s6.3) ---
            TransitionDefinition.of(START_WORK,
                    EnumSet.of(ENGINEER_ASSIGNED), IN_PROGRESS, SERVICE_ENGINEER)
                    .requiringAssignedEngineer(),

            TransitionDefinition.of(SUBMIT_COMPLETION,
                    EnumSet.of(IN_PROGRESS), COMPLETION_REPORTED, SERVICE_ENGINEER)
                    .requiringAssignedEngineer(),

            // Coordinator only. This is the Ship Manager's single completion channel.
            TransitionDefinition.of(COMPLETE,
                    EnumSet.of(COMPLETION_REPORTED), COMPLETED, SERVICE_COORDINATOR)
    );

    private static final Map<ServiceRequestAction, TransitionDefinition> BY_ACTION =
            TRANSITIONS.stream().collect(Collectors.toUnmodifiableMap(
                    TransitionDefinition::action, Function.identity()));

    private ServiceRequestWorkflow() {
    }

    static Optional<TransitionDefinition> find(ServiceRequestAction action) {
        return Optional.ofNullable(BY_ACTION.get(action));
    }

    static List<TransitionDefinition> all() {
        return TRANSITIONS;
    }

    /** Actions this role could fire from this state - drives the UI's buttons. */
    static Set<ServiceRequestAction> availableActions(ServiceRequestStatus current, Role role) {
        return TRANSITIONS.stream()
                .filter(t -> t.permitsFrom(current))
                .filter(t -> t.permitsRole(role))
                .map(TransitionDefinition::action)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ServiceRequestAction.class)));
    }
}
