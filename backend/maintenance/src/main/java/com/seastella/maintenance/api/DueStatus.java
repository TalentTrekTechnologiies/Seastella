package com.seastella.maintenance.api;

/**
 * The colour status mandated by SoW section 7 and the reference spec's
 * threshold table.
 *
 * <p>These four colours are <b>reserved</b>: nothing decorative in the UI may
 * use them, so that green always means "normal" and never "primary action".
 *
 * <p>Each status also carries a {@link #shape()}, because colour alone fails
 * greyscale printing and colour-vision deficiency - and these reports reach
 * class surveyors on paper.
 */
public enum DueStatus {

    NORMAL("Normal", "green", "dot"),
    APPROACHING("Approaching", "yellow", "half-dot"),
    URGENT("Urgent", "orange", "triangle"),
    DUE("Due", "red", "square"),
    OVERDUE("Overdue", "red", "square"),

    /** No maintenance rule configured; not a health judgement. */
    NOT_TRACKED("Not tracked", "neutral", "none");

    private final String label;
    private final String colour;
    private final String shape;

    DueStatus(String label, String colour, String shape) {
        this.label = label;
        this.colour = colour;
        this.shape = shape;
    }

    public String label() { return label; }
    public String colour() { return colour; }
    public String shape() { return shape; }

    /** Whether this status should appear in an "needs attention" count. */
    public boolean needsAttention() {
        return this == APPROACHING || this == URGENT || this == DUE || this == OVERDUE;
    }

    /** Whether this status is past or at its deadline. */
    public boolean isCritical() {
        return this == DUE || this == OVERDUE;
    }
}
