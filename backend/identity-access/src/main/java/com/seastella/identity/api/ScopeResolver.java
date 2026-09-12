package com.seastella.identity.api;

/**
 * Resolves the current principal's {@link AccessScope}.
 *
 * <p>Every access rule in the SoW reduces to one question - which vessels may
 * this principal touch - so it is answered here, once, rather than
 * re-derived in each controller. That re-derivation is precisely how
 * cross-vessel leaks get written.
 */
public interface ScopeResolver {

    /** Never null for an authenticated request; cached for the request's life. */
    AccessScope currentScope();
}
