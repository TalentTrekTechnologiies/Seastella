package com.seastella.maintenance.internal;

/**
 * Resolves a spare's owning organization, so thresholds can be overridden per
 * organization without {@code maintenance} reaching into {@code fleet}'s
 * internals. Implemented in {@code fleet}.
 */
public interface SpareOrganizationLookup {

    /** Null when the spare or its vessel cannot be resolved. */
    Long organizationIdForSpare(Long spareId);
}
