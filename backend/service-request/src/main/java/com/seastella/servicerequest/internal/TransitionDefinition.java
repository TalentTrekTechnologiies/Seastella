package com.seastella.servicerequest.internal;

import com.seastella.identity.api.Role;
import com.seastella.servicerequest.api.ServiceRequestAction;
import com.seastella.servicerequest.api.ServiceRequestStatus;

import java.util.EnumSet;
import java.util.Set;

/**
 * One edge of the state machine: which action, from which states, to which
 * state, performable by which roles.
 *
 * @param action     the named transition
 * @param fromStates legal source states; empty means "creation" (no source)
 * @param toState    the resulting state
 * @param allowedRoles roles permitted to fire it
 * @param requiresActorIsAssignedEngineer  the actor must be the request's own
 *                                         assigned engineer (guard G7)
 * @param requiresAcceptedInvoice          an accepted invoice must exist
 *                                         (guard G1 - the invoice gate)
 */
record TransitionDefinition(
        ServiceRequestAction action,
        Set<ServiceRequestStatus> fromStates,
        ServiceRequestStatus toState,
        Set<Role> allowedRoles,
        boolean requiresActorIsAssignedEngineer,
        boolean requiresAcceptedInvoice) {

    static TransitionDefinition of(ServiceRequestAction action,
                                   Set<ServiceRequestStatus> from,
                                   ServiceRequestStatus to,
                                   Role... roles) {
        return new TransitionDefinition(action, from, to,
                roles.length == 0 ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(Set.of(roles)),
                false, false);
    }

    TransitionDefinition requiringAssignedEngineer() {
        return new TransitionDefinition(action, fromStates, toState, allowedRoles, true, requiresAcceptedInvoice);
    }

    TransitionDefinition requiringAcceptedInvoice() {
        return new TransitionDefinition(action, fromStates, toState, allowedRoles, requiresActorIsAssignedEngineer, true);
    }

    boolean permitsRole(Role role) {
        return allowedRoles.contains(role);
    }

    boolean permitsFrom(ServiceRequestStatus status) {
        return fromStates.contains(status);
    }
}
