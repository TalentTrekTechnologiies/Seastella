package com.seastella.invoice.api;

/**
 * Invoice lifecycle (SoW s6.2).
 *
 * <p><b>There is deliberately no PAID state and no settlement field.</b> SoW
 * section 7 classifies payment settlement as Out of Scope - a stronger
 * classification than Phase 2 - and section 15 confirms settlement "remains
 * part of Seastella's existing finance process". This module raises, reviews
 * and accepts. Nothing here moves money.
 */
public enum InvoiceStatus {

    /** Raised by the Coordinator, awaiting the Ship Manager. */
    RAISED("Raised"),

    /** Accepted. <b>Only this state opens the engineer-assignment gate.</b> */
    ACCEPTED("Accepted"),

    /** Rejected by the Ship Manager. */
    REJECTED("Rejected"),

    /** Queried by the Ship Manager - a question, not yet a refusal. */
    QUERIED("Queried"),

    /** Replaced by a later invoice after rejection or query (OI-08). */
    SUPERSEDED("Superseded");

    private final String label;

    InvoiceStatus(String label) { this.label = label; }

    public String label() { return label; }

    /** True while the Ship Manager still owes a decision. */
    public boolean awaitsDecision() { return this == RAISED; }

    public boolean isOpen() { return this == RAISED || this == QUERIED; }
}
