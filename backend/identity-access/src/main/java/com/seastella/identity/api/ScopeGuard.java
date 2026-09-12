package com.seastella.identity.api;

/**
 * Asserts that a resource lies inside the caller's scope, throwing
 * {@code NotFoundException} when it does not.
 *
 * <p>This is layer 3 of the enforcement stack described in docs/02 section 3.
 * Layer 2 (the query filter) already narrows reads in SQL; this guard covers
 * write paths and by-id lookups, where a filter alone would let a caller
 * discover existence through timing or error shape.
 */
public interface ScopeGuard {

    /** @throws com.seastella.core.api.error.NotFoundException if out of scope */
    void assertVessel(Long vesselId);

    /** @throws com.seastella.core.api.error.NotFoundException if out of scope */
    void assertOrganization(Long organizationId);

    /** @throws com.seastella.core.api.error.ForbiddenException if the role lacks it */
    void assertFinancialAccess();
}
