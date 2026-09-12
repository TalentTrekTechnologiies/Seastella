package com.seastella.core.api.security;

import java.util.Optional;

/**
 * Supplies the acting user to modules that must not depend on
 * {@code identity-access} (notably {@code platform-core}, which sits below it).
 *
 * <p>Inverting the dependency this way is what lets the audit service stamp the
 * actor without {@code platform-core} knowing anything about users.
 */
public interface ActorProvider {

    Optional<Actor> currentActor();

    /** The authenticated principal, reduced to what auditing and scoping need. */
    record Actor(Long userId, String email, String role, Long organizationId) {}
}
