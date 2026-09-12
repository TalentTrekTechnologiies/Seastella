package com.seastella.servicerequest.api;

/**
 * The named transitions of the lifecycle. Status changes only ever happen by
 * applying one of these - {@code ServiceRequest} exposes no status setter.
 */
public enum ServiceRequestAction {

    RAISE("Raise request"),
    START_TROUBLESHOOTING("Start troubleshooting"),
    ESCALATE_TO_LIVE_AGENT("Escalate to live agent"),
    SUBMIT_FOR_APPROVAL("Submit for approval"),
    REQUEST_CLARIFICATION("Request clarification"),
    RESUBMIT("Resubmit"),
    REJECT("Reject request"),
    APPROVE_OPERATIONAL("Approve request"),
    CLOSE_NO_COST("Close without cost"),
    RAISE_INVOICE("Raise invoice"),
    ACCEPT_INVOICE("Accept invoice"),
    REJECT_INVOICE("Reject invoice"),
    QUERY_INVOICE("Query invoice"),
    ASSIGN_ENGINEER("Assign engineer"),
    START_WORK("Start work"),
    SUBMIT_COMPLETION("Submit completion report"),
    COMPLETE("Mark completed");

    private final String label;

    ServiceRequestAction(String label) { this.label = label; }

    public String label() { return label; }

    /** Actions that require a written reason from the actor. */
    public boolean requiresReason() {
        return this == REQUEST_CLARIFICATION
                || this == REJECT
                || this == REJECT_INVOICE
                || this == QUERY_INVOICE;
    }
}
