package com.seastella.servicerequest.internal;

import com.seastella.identity.api.Role;
import com.seastella.servicerequest.api.ServiceRequestAction;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties of the transition table itself, independent of any actor.
 *
 * <p>These are the structural guarantees: if the table permits something it
 * should not, these fail before any behavioural test gets the chance to.
 */
class ServiceRequestWorkflowTest {

    @Nested
    @DisplayName("the invoice gate is structural")
    class InvoiceGate {

        /**
         * G1. The single most important assertion in the codebase: engineer
         * assignment has exactly one legal source state.
         */
        @Test
        void assignEngineerHasExactlyOneLegalSourceState() {
            TransitionDefinition assign = ServiceRequestWorkflow.find(ServiceRequestAction.ASSIGN_ENGINEER)
                    .orElseThrow();

            assertThat(assign.fromStates())
                    .containsExactly(ServiceRequestStatus.INVOICE_ACCEPTED);
        }

        @Test
        void assignEngineerIsUnreachableFromEveryUnacceptedInvoiceState() {
            TransitionDefinition assign = ServiceRequestWorkflow.find(ServiceRequestAction.ASSIGN_ENGINEER)
                    .orElseThrow();

            assertThat(assign.permitsFrom(ServiceRequestStatus.INVOICE_RAISED)).isFalse();
            assertThat(assign.permitsFrom(ServiceRequestStatus.INVOICE_REJECTED)).isFalse();
            assertThat(assign.permitsFrom(ServiceRequestStatus.INVOICE_QUERIED)).isFalse();
            assertThat(assign.permitsFrom(ServiceRequestStatus.OPERATIONALLY_APPROVED)).isFalse();
            assertThat(assign.permitsFrom(ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL)).isFalse();
        }

        /** The independent re-check against invoice records is switched on. */
        @Test
        void assignEngineerAlsoRequiresAnAcceptedInvoiceRecord() {
            assertThat(ServiceRequestWorkflow.find(ServiceRequestAction.ASSIGN_ENGINEER)
                    .orElseThrow().requiresAcceptedInvoice()).isTrue();
        }

        @Test
        void onlyTheCoordinatorMayAssign() {
            TransitionDefinition assign = ServiceRequestWorkflow.find(ServiceRequestAction.ASSIGN_ENGINEER)
                    .orElseThrow();

            assertThat(assign.allowedRoles()).containsExactly(Role.SERVICE_COORDINATOR);
        }

        /** Acceptance is the Ship Manager's, never the Coordinator's own. */
        @Test
        void onlyTheShipManagerMayDecideAnInvoice() {
            for (ServiceRequestAction action : EnumSet.of(
                    ServiceRequestAction.ACCEPT_INVOICE,
                    ServiceRequestAction.REJECT_INVOICE,
                    ServiceRequestAction.QUERY_INVOICE)) {

                assertThat(ServiceRequestWorkflow.find(action).orElseThrow().allowedRoles())
                        .as("%s", action)
                        .containsExactly(Role.SHIP_MANAGER);
            }
        }
    }

    @Nested
    @DisplayName("the reporting chain is structural")
    class ReportingChain {

        /**
         * G2. The engineer reports to the Coordinator only. No transition
         * reaching COMPLETED is available to an engineer, so the Ship Manager
         * cannot be updated directly.
         */
        @Test
        void noEngineerTransitionReachesCompleted() {
            assertThat(ServiceRequestWorkflow.all())
                    .filteredOn(t -> t.toState() == ServiceRequestStatus.COMPLETED)
                    .allSatisfy(t -> assertThat(t.allowedRoles())
                            .doesNotContain(Role.SERVICE_ENGINEER));
        }

        @Test
        void onlyTheCoordinatorMayMarkCompleted() {
            assertThat(ServiceRequestWorkflow.find(ServiceRequestAction.COMPLETE)
                    .orElseThrow().allowedRoles())
                    .containsExactly(Role.SERVICE_COORDINATOR);
        }

        @Test
        void theEngineersFurthestReachIsCompletionReported() {
            Set<ServiceRequestStatus> reachable = EnumSet.noneOf(ServiceRequestStatus.class);
            ServiceRequestWorkflow.all().stream()
                    .filter(t -> t.permitsRole(Role.SERVICE_ENGINEER))
                    .forEach(t -> reachable.add(t.toState()));

            assertThat(reachable).containsExactlyInAnyOrder(
                    ServiceRequestStatus.IN_PROGRESS,
                    ServiceRequestStatus.COMPLETION_REPORTED);
        }

        /** Engineer actions must be bound to that engineer's own job (G7). */
        @Test
        void engineerActionsRequireTheActorBeTheAssignedEngineer() {
            assertThat(ServiceRequestWorkflow.find(ServiceRequestAction.START_WORK)
                    .orElseThrow().requiresActorIsAssignedEngineer()).isTrue();
            assertThat(ServiceRequestWorkflow.find(ServiceRequestAction.SUBMIT_COMPLETION)
                    .orElseThrow().requiresActorIsAssignedEngineer()).isTrue();
        }
    }

    @Nested
    @DisplayName("separation of operational and financial approval")
    class SeparationOfDuties {

        /** G3. Approving operationally never implies accepting an invoice. */
        @Test
        void operationalApprovalAndInvoiceAcceptanceAreDistinctEdges() {
            TransitionDefinition operational =
                    ServiceRequestWorkflow.find(ServiceRequestAction.APPROVE_OPERATIONAL).orElseThrow();
            TransitionDefinition financial =
                    ServiceRequestWorkflow.find(ServiceRequestAction.ACCEPT_INVOICE).orElseThrow();

            assertThat(operational.toState()).isEqualTo(ServiceRequestStatus.OPERATIONALLY_APPROVED);
            assertThat(financial.toState()).isEqualTo(ServiceRequestStatus.INVOICE_ACCEPTED);
            assertThat(operational.fromStates()).doesNotContainAnyElementsOf(financial.fromStates());
        }

        /** Only the Captain raises. No shore role may raise on a vessel's behalf. */
        @Test
        void onlyTheCaptainRaisesARequest() {
            assertThat(ServiceRequestWorkflow.find(ServiceRequestAction.RAISE)
                    .orElseThrow().allowedRoles())
                    .containsExactly(Role.CAPTAIN);
        }
    }

    @Nested
    @DisplayName("table integrity")
    class Integrity {

        @Test
        void everyActionHasExactlyOneDefinition() {
            for (ServiceRequestAction action : ServiceRequestAction.values()) {
                assertThat(ServiceRequestWorkflow.find(action))
                        .as("definition for %s", action)
                        .isPresent();
            }
            assertThat(ServiceRequestWorkflow.all()).hasSize(ServiceRequestAction.values().length);
        }

        /** Nothing leaves a terminal state. */
        @Test
        void noTransitionLeavesATerminalState() {
            for (ServiceRequestStatus terminal : EnumSet.of(
                    ServiceRequestStatus.COMPLETED,
                    ServiceRequestStatus.REJECTED,
                    ServiceRequestStatus.CLOSED_NO_COST)) {

                assertThat(ServiceRequestWorkflow.all())
                        .as("transitions out of %s", terminal)
                        .noneMatch(t -> t.permitsFrom(terminal));
            }
        }

        /** Every non-creation state is reachable, so no state is dead. */
        @Test
        void everyStateIsReachable() {
            Set<ServiceRequestStatus> reachable = EnumSet.of(ServiceRequestStatus.REPORTED);
            ServiceRequestWorkflow.all().forEach(t -> reachable.add(t.toState()));

            assertThat(reachable).containsAll(EnumSet.allOf(ServiceRequestStatus.class));
        }

        @Test
        void availableActionsReflectRoleAndState() {
            assertThat(ServiceRequestWorkflow.availableActions(
                    ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL, Role.SHIP_MANAGER))
                    .containsExactlyInAnyOrder(
                            ServiceRequestAction.APPROVE_OPERATIONAL,
                            ServiceRequestAction.REJECT,
                            ServiceRequestAction.REQUEST_CLARIFICATION);

            // The Captain has nothing to do while the Ship Manager reviews.
            assertThat(ServiceRequestWorkflow.availableActions(
                    ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL, Role.CAPTAIN))
                    .isEmpty();

            // The Coordinator can assign only once the invoice is accepted.
            assertThat(ServiceRequestWorkflow.availableActions(
                    ServiceRequestStatus.INVOICE_RAISED, Role.SERVICE_COORDINATOR))
                    .doesNotContain(ServiceRequestAction.ASSIGN_ENGINEER);
            assertThat(ServiceRequestWorkflow.availableActions(
                    ServiceRequestStatus.INVOICE_ACCEPTED, Role.SERVICE_COORDINATOR))
                    .contains(ServiceRequestAction.ASSIGN_ENGINEER);
        }
    }
}
