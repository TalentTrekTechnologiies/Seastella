package com.seastella.servicerequest.api;

import java.time.LocalDate;

/**
 * Writes to the service request lifecycle, for the controllers and for modules
 * that own a record a transition depends on (an invoice, a completion report).
 *
 * <p>Every method acts as the signed-in user. The actor's id and role come from
 * the resolved scope, never from the caller, and every change still passes
 * through the state machine's guards: scope, role, source state, engineer
 * identity, the invoice gate and required reasons.
 */
public interface ServiceRequestCommands {

    /** Captain raises a request against a spare on their own vessel. Returns the new id. */
    Long raise(Raise command);

    /** Applies a named transition as the current user. */
    void transition(Long serviceRequestId, ServiceRequestAction action, String reason, Long engineerUserId);

    /** The assigned engineer files the completion report, moving the job to Completion reported. */
    void submitCompletion(Long serviceRequestId, Completion report);

    /** The request's vessel and organization, after confirming it is in the caller's scope. */
    Placement placementInScope(Long serviceRequestId);

    /** Records which kind of problem the Captain reported; it selects the guided checks. */
    void recordProblemType(Long serviceRequestId, Long problemTypeId);

    record Raise(Long spareId, String title, String description, Priority priority) {}

    record Completion(String workPerformed, String partsUsed, String outcome, LocalDate serviceDate) {}

    record Placement(Long serviceRequestId, String requestNumber, Long organizationId, Long vesselId) {}
}
