package com.seastella.core.api.error;

import org.springframework.http.HttpStatus;

/**
 * A state-machine guard rejected the transition (docs/04 s5, guards G1-G8).
 *
 * <p>409 rather than 400: the request is well-formed and the caller may well be
 * permitted the action - it is the record's current state that forbids it. The
 * message <em>is</em> exposed, because "an engineer cannot be assigned until the
 * invoice is accepted" is exactly what the Coordinator needs to read.
 */
public class WorkflowException extends ApiException {

    public WorkflowException(String message) {
        super(HttpStatus.CONFLICT, "WORKFLOW_VIOLATION", message);
    }

    @Override
    public boolean isMessageSafeToExpose() { return true; }
}
