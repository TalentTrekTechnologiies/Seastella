package com.seastella.servicerequest.internal;

import com.seastella.identity.api.Role;
import com.seastella.servicerequest.api.ServiceRequestAction;

/**
 * Everything the machine needs to decide one transition.
 *
 * @param action        what is being attempted
 * @param actorUserId   who is attempting it
 * @param actorRole     the actor's role
 * @param reason        rejection / query / clarification text, where required
 * @param engineerUserId target engineer, for ASSIGN_ENGINEER only
 */
record TransitionRequest(
        ServiceRequestAction action,
        Long actorUserId,
        Role actorRole,
        String reason,
        Long engineerUserId) {

    static TransitionRequest of(ServiceRequestAction action, Long actorUserId, Role actorRole) {
        return new TransitionRequest(action, actorUserId, actorRole, null, null);
    }

    static TransitionRequest withReason(ServiceRequestAction action, Long actorUserId,
                                        Role actorRole, String reason) {
        return new TransitionRequest(action, actorUserId, actorRole, reason, null);
    }

    static TransitionRequest assignment(Long actorUserId, Role actorRole, Long engineerUserId) {
        return new TransitionRequest(ServiceRequestAction.ASSIGN_ENGINEER,
                actorUserId, actorRole, null, engineerUserId);
    }
}
