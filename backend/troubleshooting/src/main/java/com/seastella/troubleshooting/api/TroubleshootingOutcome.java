package com.seastella.troubleshooting.api;

/**
 * Where the guided checks ended (SoW s18: "logs the outcome (resolved /
 * temporary fix / unresolved)").
 *
 * <p>None of these changes the request's status by itself. SoW s6.2 routes even
 * a resolved request through the Ship Manager and the Coordinator, who closes it
 * without cost; a temporary fix still leaves the follow-up decision with the
 * Coordinator (OI-11).
 */
public enum TroubleshootingOutcome {

    RESOLVED("Resolved"),
    TEMPORARY_FIX("Temporary fix"),
    UNRESOLVED("Not resolved");

    private final String label;

    TroubleshootingOutcome(String label) {
        this.label = label;
    }

    public String label() { return label; }
}
