package com.seastella.identity.api;

/**
 * How broadly a principal may see data. Resolved once per request and applied
 * to every scoped query (docs/02 section 3).
 */
public enum ScopeKind {

    /** No narrowing. Platform Admin only. */
    PLATFORM,

    /** Narrowed to one organization's vessels. */
    ORGANIZATION,

    /** Narrowed to an explicit set of vessel ids. */
    VESSEL_SET,

    /**
     * Narrowed to an explicit set of service-request ids - the Service Engineer.
     *
     * <p>Deliberately distinct from {@link #VESSEL_SET}: an engineer reaches a
     * vessel only transitively through an assigned job, and there is no code
     * path that converts one scope kind into the other.
     */
    JOB_SET
}
