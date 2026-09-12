package com.seastella.servicerequest.api;

/**
 * The Service Request lifecycle from SoW section 6.
 *
 * <p>Every state here maps to a step the SoW names explicitly. No state exists
 * for bookkeeping convenience. In particular there is deliberately <em>no</em>
 * "resolved by assistant" state: SoW section 6.2 routes a resolved request
 * through the Coordinator, who closes it as {@link #CLOSED_NO_COST}. The
 * troubleshooting outcome is recorded on the session, not on the request.
 */
public enum ServiceRequestStatus {

    /** Captain has raised it against a spare (s6.1 step 1). */
    REPORTED("Reported", false),

    /** The assistant engaged on submission (s6.1 step 2). */
    TROUBLESHOOTING("Troubleshooting", false),

    /** Guided checks did not resolve it; live chat opened (s6.1 step 3). */
    LIVE_AGENT_ESCALATED("Live agent", false),

    /** Awaiting the Ship Manager's operational review (s6.1 step 5). */
    PENDING_OPERATIONAL_APPROVAL("Pending approval", false),

    /** Ship Manager asked for more information; returns to pending. */
    CLARIFICATION_REQUESTED("Clarification requested", false),

    /** Ship Manager rejected it operationally. Terminal. */
    REJECTED("Rejected", true),

    /** Approved and forwarded to the Coordinator (s6.1 step 6). */
    OPERATIONALLY_APPROVED("Approved", false),

    /** Coordinator closed it - already resolved, no invoice (s6.2 step 1). Terminal. */
    CLOSED_NO_COST("Closed, no cost", true),

    /** Coordinator raised an invoice against it (s6.2 step 2). */
    INVOICE_RAISED("Invoice raised", false),

    /** Ship Manager queried the invoice (s6.2 step 3). */
    INVOICE_QUERIED("Invoice queried", false),

    /** Ship Manager rejected the invoice (s6.2 step 3). */
    INVOICE_REJECTED("Invoice rejected", false),

    /**
     * Ship Manager accepted the invoice. <b>This is the only state from which an
     * engineer may be assigned</b> (s6.2 step 4).
     */
    INVOICE_ACCEPTED("Invoice accepted", false),

    /** Coordinator assigned a Service Engineer (s6.2 step 5). */
    ENGINEER_ASSIGNED("Engineer assigned", false),

    /** Engineer has started the service (s6.3 step 1). */
    IN_PROGRESS("In progress", false),

    /** Engineer reported completion to the Coordinator <em>only</em> (s6.3 step 1). */
    COMPLETION_REPORTED("Completion reported", false),

    /** Coordinator reconciled cost and relayed to the Ship Manager (s6.3 step 2). Terminal. */
    COMPLETED("Completed", true);

    private final String label;
    private final boolean terminal;

    ServiceRequestStatus(String label, boolean terminal) {
        this.label = label;
        this.terminal = terminal;
    }

    public String label() { return label; }

    /** No transition leaves a terminal state. */
    public boolean isTerminal() { return terminal; }

    /** True once the Coordinator owns the request (dashboard grouping). */
    public boolean isWithCoordinator() {
        return this == OPERATIONALLY_APPROVED
                || this == INVOICE_RAISED
                || this == INVOICE_QUERIED
                || this == INVOICE_REJECTED
                || this == INVOICE_ACCEPTED
                || this == ENGINEER_ASSIGNED
                || this == IN_PROGRESS
                || this == COMPLETION_REPORTED;
    }

    /** True while the request is still live work. */
    public boolean isOpen() {
        return !terminal;
    }

    /** True when the Ship Manager has an action waiting. */
    public boolean awaitsShipManager() {
        return this == PENDING_OPERATIONAL_APPROVAL || this == INVOICE_RAISED;
    }
}
