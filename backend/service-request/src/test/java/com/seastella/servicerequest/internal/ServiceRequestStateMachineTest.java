package com.seastella.servicerequest.internal;

import com.seastella.core.api.error.ForbiddenException;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
import com.seastella.core.api.event.DomainEvent;
import com.seastella.core.api.event.DomainEventPublisher;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.servicerequest.api.InvoiceGateQuery;
import com.seastella.servicerequest.api.ServiceRequestAction;
import com.seastella.servicerequest.api.ServiceRequestEvents;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.Set;

import static com.seastella.servicerequest.internal.TestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behaviour of the state machine executor: the guards, in order, on real
 * transition attempts.
 */
class ServiceRequestStateMachineTest {

    private ServiceRequestRepository requests;
    private ServiceRequestTransitionLogRepository transitionLog;
    private ScopeResolver scopeResolver;
    private InvoiceGateQuery invoiceGate;
    private DomainEventPublisher events;
    private ServiceRequestStateMachine machine;

    @BeforeEach
    void setUp() {
        requests = mock(ServiceRequestRepository.class);
        transitionLog = mock(ServiceRequestTransitionLogRepository.class);
        scopeResolver = mock(ScopeResolver.class);
        invoiceGate = mock(InvoiceGateQuery.class);
        events = mock(DomainEventPublisher.class);

        when(requests.save(any(ServiceRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        machine = new ServiceRequestStateMachine(
                requests, transitionLog, scopeResolver, invoiceGate, events);
    }

    private void scope(AccessScope scope) {
        when(scopeResolver.currentScope()).thenReturn(scope);
    }

    // =====================================================================
    //  THE INVOICE GATE - the seven required cases
    // =====================================================================

    @Nested
    @DisplayName("invoice gate (G1)")
    class InvoiceGateCases {

        @BeforeEach
        void coordinatorInScope() {
            scope(orgScope(Role.SERVICE_COORDINATOR, COORDINATOR_ID));
        }

        /** Case 1: assignment before any invoice acceptance is refused. */
        @Test
        void assignmentBeforeInvoiceAcceptanceIsRejected() {
            ServiceRequest request = request(ServiceRequestStatus.INVOICE_RAISED);
            when(invoiceGate.hasAcceptedInvoice(anyLong())).thenReturn(false);

            assertThatThrownBy(() -> machine.applyTo(request,
                    TransitionRequest.assignment(COORDINATOR_ID, Role.SERVICE_COORDINATOR, ENGINEER_ID)))
                    .isInstanceOf(WorkflowException.class)
                    .hasMessageContaining("not permitted while the request is Invoice raised");

            assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.INVOICE_RAISED);
            assertThat(request.getAssignedEngineerUserId()).isNull();
            verify(events, never()).publish(any());
        }

        /** Case 2: assignment after acceptance is allowed. */
        @Test
        void assignmentAfterInvoiceAcceptanceIsAllowed() {
            ServiceRequest request = request(ServiceRequestStatus.INVOICE_ACCEPTED);
            when(invoiceGate.hasAcceptedInvoice(REQUEST_ID)).thenReturn(true);

            machine.applyTo(request,
                    TransitionRequest.assignment(COORDINATOR_ID, Role.SERVICE_COORDINATOR, ENGINEER_ID));

            assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.ENGINEER_ASSIGNED);
            assertThat(request.getAssignedEngineerUserId()).isEqualTo(ENGINEER_ID);
            assertThat(request.getAssignedByUserId()).isEqualTo(COORDINATOR_ID);
            assertThat(request.getAssignedAt()).isNotNull();
        }

        /** Case 3: a rejected invoice does not open the gate. */
        @Test
        void assignmentOnRejectedInvoiceIsRejected() {
            ServiceRequest request = request(ServiceRequestStatus.INVOICE_REJECTED);
            when(invoiceGate.hasAcceptedInvoice(anyLong())).thenReturn(false);

            assertThatThrownBy(() -> machine.applyTo(request,
                    TransitionRequest.assignment(COORDINATOR_ID, Role.SERVICE_COORDINATOR, ENGINEER_ID)))
                    .isInstanceOf(WorkflowException.class);

            assertThat(request.getAssignedEngineerUserId()).isNull();
        }

        /** Case 4: a queried invoice does not open the gate. */
        @Test
        void assignmentOnQueriedInvoiceIsRejected() {
            ServiceRequest request = request(ServiceRequestStatus.INVOICE_QUERIED);
            when(invoiceGate.hasAcceptedInvoice(anyLong())).thenReturn(false);

            assertThatThrownBy(() -> machine.applyTo(request,
                    TransitionRequest.assignment(COORDINATOR_ID, Role.SERVICE_COORDINATOR, ENGINEER_ID)))
                    .isInstanceOf(WorkflowException.class);

            assertThat(request.getAssignedEngineerUserId()).isNull();
        }

        /**
         * The defence-in-depth case: even with the status column reading
         * INVOICE_ACCEPTED, assignment is refused when no accepted invoice
         * record actually exists. This is what makes the gate a property of the
         * system rather than of one column.
         */
        @Test
        void assignmentIsRejectedWhenStatusSaysAcceptedButNoInvoiceRecordExists() {
            ServiceRequest request = request(ServiceRequestStatus.INVOICE_ACCEPTED);
            when(invoiceGate.hasAcceptedInvoice(REQUEST_ID)).thenReturn(false);

            assertThatThrownBy(() -> machine.applyTo(request,
                    TransitionRequest.assignment(COORDINATOR_ID, Role.SERVICE_COORDINATOR, ENGINEER_ID)))
                    .isInstanceOf(WorkflowException.class)
                    .hasMessageContaining("accepted an invoice");

            assertThat(request.getAssignedEngineerUserId()).isNull();
        }

        /** Case 5: a role other than Coordinator may not assign. */
        @Test
        void unauthorizedRoleCannotAssign() {
            when(invoiceGate.hasAcceptedInvoice(anyLong())).thenReturn(true);

            for (Role role : Set.of(Role.CAPTAIN, Role.SHIP_MANAGER,
                    Role.TECHNICAL_HEAD, Role.SERVICE_ENGINEER)) {

                ServiceRequest request = request(ServiceRequestStatus.INVOICE_ACCEPTED);
                scope(orgScope(role, 999L));

                assertThatThrownBy(() -> machine.applyTo(request,
                        TransitionRequest.assignment(999L, role, ENGINEER_ID)))
                        .as("%s attempting assignment", role)
                        .isInstanceOf(ForbiddenException.class);

                assertThat(request.getAssignedEngineerUserId()).isNull();
            }
        }

        /** Case 6: a coordinator scoped to other vessels cannot assign. */
        @Test
        void coordinatorFromAnotherVesselCannotAssign() {
            ServiceRequest request = request(ServiceRequestStatus.INVOICE_ACCEPTED);
            when(invoiceGate.hasAcceptedInvoice(anyLong())).thenReturn(true);

            // Scoped to vessel A2 only; the request is on A1.
            scope(vesselScope(Role.SERVICE_COORDINATOR, COORDINATOR_ID, VESSEL_A2));

            assertThatThrownBy(() -> machine.applyTo(request,
                    TransitionRequest.assignment(COORDINATOR_ID, Role.SERVICE_COORDINATOR, ENGINEER_ID)))
                    .isInstanceOf(NotFoundException.class);

            assertThat(request.getAssignedEngineerUserId()).isNull();
        }

        /** Case 7: a coordinator in another organization cannot assign. */
        @Test
        void coordinatorFromAnotherOrganizationCannotAssign() {
            ServiceRequest request = request(ServiceRequestStatus.INVOICE_ACCEPTED);
            when(invoiceGate.hasAcceptedInvoice(anyLong())).thenReturn(true);

            scope(otherOrgScope(Role.SERVICE_COORDINATOR, 777L));

            assertThatThrownBy(() -> machine.applyTo(request,
                    TransitionRequest.assignment(777L, Role.SERVICE_COORDINATOR, ENGINEER_ID)))
                    .isInstanceOf(NotFoundException.class);

            assertThat(request.getAssignedEngineerUserId()).isNull();
        }

        /** Assignment must name an engineer. */
        @Test
        void assignmentWithoutATargetEngineerIsRejected() {
            ServiceRequest request = request(ServiceRequestStatus.INVOICE_ACCEPTED);
            when(invoiceGate.hasAcceptedInvoice(REQUEST_ID)).thenReturn(true);

            assertThatThrownBy(() -> machine.applyTo(request,
                    new TransitionRequest(ServiceRequestAction.ASSIGN_ENGINEER,
                            COORDINATOR_ID, Role.SERVICE_COORDINATOR, null, null)))
                    .isInstanceOf(ValidationException.class);
        }
    }

    // =====================================================================
    //  VALID / INVALID TRANSITIONS
    // =====================================================================

    @Nested
    @DisplayName("valid transitions")
    class ValidTransitions {

        @Test
        void shipManagerApprovesOperationally() {
            ServiceRequest request = request(ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL);
            scope(vesselScope(Role.SHIP_MANAGER, SHIP_MANAGER_ID, VESSEL_A1));

            machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.APPROVE_OPERATIONAL, SHIP_MANAGER_ID, Role.SHIP_MANAGER));

            assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.OPERATIONALLY_APPROVED);
            assertThat(request.getOperationalApprovedByUserId()).isEqualTo(SHIP_MANAGER_ID);
            assertThat(request.getOperationalApprovedAt()).isNotNull();
        }

        @Test
        void captainEscalatesToLiveAgent() {
            ServiceRequest request = request(ServiceRequestStatus.TROUBLESHOOTING);
            scope(vesselScope(Role.CAPTAIN, CAPTAIN_ID, VESSEL_A1));

            machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.ESCALATE_TO_LIVE_AGENT, CAPTAIN_ID, Role.CAPTAIN));

            assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.LIVE_AGENT_ESCALATED);
        }

        @Test
        void coordinatorClosesWithoutCost() {
            ServiceRequest request = request(ServiceRequestStatus.OPERATIONALLY_APPROVED);
            scope(orgScope(Role.SERVICE_COORDINATOR, COORDINATOR_ID));

            machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.CLOSE_NO_COST, COORDINATOR_ID, Role.SERVICE_COORDINATOR));

            assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.CLOSED_NO_COST);
            assertThat(request.getClosedAt()).isNotNull();
        }

        @Test
        void everyTransitionIsLoggedAndPublished() {
            ServiceRequest request = request(ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL);
            scope(vesselScope(Role.SHIP_MANAGER, SHIP_MANAGER_ID, VESSEL_A1));

            machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.APPROVE_OPERATIONAL, SHIP_MANAGER_ID, Role.SHIP_MANAGER));

            ArgumentCaptor<ServiceRequestTransitionLog> logged =
                    ArgumentCaptor.forClass(ServiceRequestTransitionLog.class);
            verify(transitionLog).save(logged.capture());

            assertThat(logged.getValue().getFromStatus())
                    .isEqualTo(ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL);
            assertThat(logged.getValue().getToStatus())
                    .isEqualTo(ServiceRequestStatus.OPERATIONALLY_APPROVED);
            assertThat(logged.getValue().getActorUserId()).isEqualTo(SHIP_MANAGER_ID);
            assertThat(logged.getValue().getActorRole()).isEqualTo("SHIP_MANAGER");
            assertThat(logged.getValue().getOccurredAt()).isNotNull();

            ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
            verify(events).publish(published.capture());
            assertThat(published.getValue())
                    .isInstanceOf(ServiceRequestEvents.Transitioned.class);
        }
    }

    @Nested
    @DisplayName("invalid transitions")
    class InvalidTransitions {

        /** G6: stages cannot be skipped. */
        @Test
        void cannotSkipFromTroubleshootingStraightToApproved() {
            ServiceRequest request = request(ServiceRequestStatus.TROUBLESHOOTING);
            scope(vesselScope(Role.SHIP_MANAGER, SHIP_MANAGER_ID, VESSEL_A1));

            assertThatThrownBy(() -> machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.APPROVE_OPERATIONAL, SHIP_MANAGER_ID, Role.SHIP_MANAGER)))
                    .isInstanceOf(WorkflowException.class)
                    .hasMessageContaining("not permitted while the request is Troubleshooting");
        }

        @Test
        void cannotChangeATerminalRequest() {
            ServiceRequest request = request(ServiceRequestStatus.COMPLETED);
            scope(orgScope(Role.SERVICE_COORDINATOR, COORDINATOR_ID));

            assertThatThrownBy(() -> machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.CLOSE_NO_COST, COORDINATOR_ID, Role.SERVICE_COORDINATOR)))
                    .isInstanceOf(WorkflowException.class)
                    .hasMessageContaining("cannot be changed further");
        }

        @Test
        void rejectionRequiresAReason() {
            ServiceRequest request = request(ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL);
            scope(vesselScope(Role.SHIP_MANAGER, SHIP_MANAGER_ID, VESSEL_A1));

            assertThatThrownBy(() -> machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.REJECT, SHIP_MANAGER_ID, Role.SHIP_MANAGER)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("requires a reason");
        }

        @Test
        void rejectionWithAReasonSucceedsAndRecordsIt() {
            ServiceRequest request = request(ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL);
            scope(vesselScope(Role.SHIP_MANAGER, SHIP_MANAGER_ID, VESSEL_A1));

            machine.applyTo(request, TransitionRequest.withReason(
                    ServiceRequestAction.REJECT, SHIP_MANAGER_ID, Role.SHIP_MANAGER,
                    "Spare is under warranty; raise with the maker instead."));

            assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.REJECTED);
            assertThat(request.getLastReason()).contains("under warranty");
        }
    }

    // =====================================================================
    //  ROLE AND SCOPE RESTRICTIONS
    // =====================================================================

    @Nested
    @DisplayName("role restrictions")
    class RoleRestrictions {

        /** G2: the engineer cannot complete - only report. */
        @Test
        void engineerCannotMarkCompleted() {
            ServiceRequest request = assignedRequest(
                    ServiceRequestStatus.COMPLETION_REPORTED, ENGINEER_ID);
            scope(jobScope(ENGINEER_ID, Set.of(REQUEST_ID), Set.of(VESSEL_A1)));

            assertThatThrownBy(() -> machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.COMPLETE, ENGINEER_ID, Role.SERVICE_ENGINEER)))
                    .isInstanceOf(ForbiddenException.class);

            assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.COMPLETION_REPORTED);
        }

        @Test
        void captainCannotApproveOwnRequest() {
            ServiceRequest request = request(ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL);
            scope(vesselScope(Role.CAPTAIN, CAPTAIN_ID, VESSEL_A1));

            assertThatThrownBy(() -> machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.APPROVE_OPERATIONAL, CAPTAIN_ID, Role.CAPTAIN)))
                    .isInstanceOf(ForbiddenException.class);
        }

        /** The Coordinator raises the invoice; the Ship Manager accepts it. */
        @Test
        void coordinatorCannotAcceptOwnInvoice() {
            ServiceRequest request = request(ServiceRequestStatus.INVOICE_RAISED);
            scope(orgScope(Role.SERVICE_COORDINATOR, COORDINATOR_ID));

            assertThatThrownBy(() -> machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.ACCEPT_INVOICE, COORDINATOR_ID, Role.SERVICE_COORDINATOR)))
                    .isInstanceOf(ForbiddenException.class);
        }

        /** The Technical Head monitors; it has no operational data entry (SoW s5). */
        @Test
        void technicalHeadHasNoWorkflowActions() {
            for (ServiceRequestStatus status : ServiceRequestStatus.values()) {
                assertThat(ServiceRequestWorkflow.availableActions(status, Role.TECHNICAL_HEAD))
                        .as("technical head actions from %s", status)
                        .isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("scope restrictions")
    class ScopeRestrictions {

        @Test
        void shipManagerCannotApproveOnAnUnallocatedVessel() {
            ServiceRequest request = request(ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL);
            scope(vesselScope(Role.SHIP_MANAGER, SHIP_MANAGER_ID, VESSEL_A2));

            assertThatThrownBy(() -> machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.APPROVE_OPERATIONAL, SHIP_MANAGER_ID, Role.SHIP_MANAGER)))
                    .isInstanceOf(NotFoundException.class);
        }

        /** G7: an engineer may act only on their own assigned job. */
        @Test
        void engineerCannotActOnAnotherEngineersJob() {
            ServiceRequest request = assignedRequest(
                    ServiceRequestStatus.ENGINEER_ASSIGNED, OTHER_ENGINEER_ID);
            scope(jobScope(ENGINEER_ID, Set.of(REQUEST_ID), Set.of(VESSEL_A1)));

            assertThatThrownBy(() -> machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.START_WORK, ENGINEER_ID, Role.SERVICE_ENGINEER)))
                    .isInstanceOf(NotFoundException.class);
        }

        /** An engineer's reach is the job set - a vessel alone is not enough. */
        @Test
        void engineerWithoutTheJobInScopeIsRefused() {
            ServiceRequest request = assignedRequest(
                    ServiceRequestStatus.ENGINEER_ASSIGNED, ENGINEER_ID);
            scope(jobScope(ENGINEER_ID, Set.of(999L), Set.of(VESSEL_A1)));

            assertThatThrownBy(() -> machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.START_WORK, ENGINEER_ID, Role.SERVICE_ENGINEER)))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void engineerWithTheJobInScopeMayStartWork() {
            ServiceRequest request = assignedRequest(
                    ServiceRequestStatus.ENGINEER_ASSIGNED, ENGINEER_ID);
            scope(jobScope(ENGINEER_ID, Set.of(REQUEST_ID), Set.of(VESSEL_A1)));

            machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.START_WORK, ENGINEER_ID, Role.SERVICE_ENGINEER));

            assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.IN_PROGRESS);
        }

        @Test
        void platformAdminScopeSeesEveryVesselButStillObeysRoleRules() {
            ServiceRequest request = request(ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL);
            scope(platformScope());

            // In scope, but PLATFORM_ADMIN is not an operational approver.
            assertThatThrownBy(() -> machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.APPROVE_OPERATIONAL, 1L, Role.PLATFORM_ADMIN)))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void missingRequestIsNotFound() {
            scope(orgScope(Role.SERVICE_COORDINATOR, COORDINATOR_ID));
            when(requests.findById(12345L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> machine.apply(12345L, TransitionRequest.of(
                    ServiceRequestAction.CLOSE_NO_COST, COORDINATOR_ID, Role.SERVICE_COORDINATOR)))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // =====================================================================
    //  FULL HAPPY PATH
    // =====================================================================

    @Nested
    @DisplayName("end-to-end lifecycle")
    class Lifecycle {

        /** The complete SoW section 6 flow, in order, with the gate in the middle. */
        @Test
        void walksTheWholeWorkflow() {
            ServiceRequest request = request(ServiceRequestStatus.REPORTED);

            scope(vesselScope(Role.CAPTAIN, CAPTAIN_ID, VESSEL_A1));
            machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.START_TROUBLESHOOTING, CAPTAIN_ID, Role.CAPTAIN));
            assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.TROUBLESHOOTING);

            machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.ESCALATE_TO_LIVE_AGENT, CAPTAIN_ID, Role.CAPTAIN));
            machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.SUBMIT_FOR_APPROVAL, CAPTAIN_ID, Role.CAPTAIN));
            assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL);

            scope(vesselScope(Role.SHIP_MANAGER, SHIP_MANAGER_ID, VESSEL_A1));
            machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.APPROVE_OPERATIONAL, SHIP_MANAGER_ID, Role.SHIP_MANAGER));

            scope(orgScope(Role.SERVICE_COORDINATOR, COORDINATOR_ID));
            machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.RAISE_INVOICE, COORDINATOR_ID, Role.SERVICE_COORDINATOR));
            assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.INVOICE_RAISED);

            // Gate closed at this point.
            when(invoiceGate.hasAcceptedInvoice(REQUEST_ID)).thenReturn(false);
            assertThatThrownBy(() -> machine.applyTo(request,
                    TransitionRequest.assignment(COORDINATOR_ID, Role.SERVICE_COORDINATOR, ENGINEER_ID)))
                    .isInstanceOf(WorkflowException.class);

            scope(vesselScope(Role.SHIP_MANAGER, SHIP_MANAGER_ID, VESSEL_A1));
            machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.ACCEPT_INVOICE, SHIP_MANAGER_ID, Role.SHIP_MANAGER));
            assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.INVOICE_ACCEPTED);

            // Gate now open.
            when(invoiceGate.hasAcceptedInvoice(REQUEST_ID)).thenReturn(true);
            scope(orgScope(Role.SERVICE_COORDINATOR, COORDINATOR_ID));
            machine.applyTo(request,
                    TransitionRequest.assignment(COORDINATOR_ID, Role.SERVICE_COORDINATOR, ENGINEER_ID));
            assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.ENGINEER_ASSIGNED);

            scope(jobScope(ENGINEER_ID, Set.of(REQUEST_ID), Set.of(VESSEL_A1)));
            machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.START_WORK, ENGINEER_ID, Role.SERVICE_ENGINEER));
            machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.SUBMIT_COMPLETION, ENGINEER_ID, Role.SERVICE_ENGINEER));
            assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.COMPLETION_REPORTED);

            // Only the Coordinator closes the loop to the Ship Manager.
            scope(orgScope(Role.SERVICE_COORDINATOR, COORDINATOR_ID));
            machine.applyTo(request, TransitionRequest.of(
                    ServiceRequestAction.COMPLETE, COORDINATOR_ID, Role.SERVICE_COORDINATOR));

            assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.COMPLETED);
            assertThat(request.getClosedAt()).isNotNull();
            assertThat(request.getResolutionType())
                    .isEqualTo(com.seastella.servicerequest.api.ResolutionType.ENGINEER_VISIT);
        }
    }
}
