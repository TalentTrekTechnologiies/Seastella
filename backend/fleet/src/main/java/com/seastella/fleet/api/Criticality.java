package com.seastella.fleet.api;

/**
 * How badly a failure of this spare hurts.
 *
 * <p>Distinct from maintenance due-status: a CRITICAL spare can be perfectly
 * in-date, and an overdue spare can be LOW criticality. Conflating the two is
 * why the design system gives criticality its own colour (plum) rather than
 * reusing the reserved green/yellow/orange/red.
 */
public enum Criticality {
    CRITICAL, HIGH, MEDIUM, LOW
}
