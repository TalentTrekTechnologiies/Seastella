package com.seastella.identity.api;

/**
 * How broadly a principal may see data. Resolved once per request and applied
 * to every scoped query (docs/02 section 3).
 */
public enum ScopeKind {

    /** No narrowing. Platform Admin only. */
    PLATFORM,

    /** Narrowed to one organization's vessels. Client-tenant roles. */
    ORGANIZATION,

    /**
     * Narrowed to an explicit set of organizations.
     *
     * <p>For Seastella's own service staff. SoW section 5 labels the role
     * "Service Coordinator (Seastella)" - the only role carrying that
     * parenthetical - and gives its access scope as "Assigned service scope"
     * rather than "own organization" as every client-side role does. Section 2
     * has Seastella serving "ship-management companies", plural, and section
     * 4.1 defines an Organization as "the client company". A Coordinator is
     * therefore platform-side staff assigned to a set of client organizations,
     * not a member of one.
     *
     * <p>The set is resolved server-side from {@code user_organization_assignment};
     * it is never taken from a request parameter.
     */
    ORGANIZATION_SET,

    /**
     * Narrowed to an explicit set of service-request ids - the Service Engineer.
     *
     * <p>Deliberately distinct from {@link #VESSEL_SET}: an engineer reaches a
     * vessel only transitively through an assigned job, and there is no code
     * path that converts one scope kind into the other. The job remains the
     * lowest-level assignment boundary.
     */
    JOB_SET,

    /** Narrowed to an explicit set of vessel ids. */
    VESSEL_SET
}
