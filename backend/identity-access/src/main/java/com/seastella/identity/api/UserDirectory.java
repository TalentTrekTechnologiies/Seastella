package com.seastella.identity.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only lookup of users for other modules.
 *
 * <p>Workflow modules need a person's name and role — to label a timeline, or
 * to confirm that the engineer being assigned really is a Service Engineer —
 * without reaching into identity's entities. This port exposes exactly that
 * and nothing that could grant access.
 */
public interface UserDirectory {

    Optional<UserRef> find(Long userId);

    /** Names for a set of ids, in one query. Unknown ids are simply absent. */
    Map<Long, UserRef> findAll(Collection<Long> userIds);

    /** Active users holding a role, by name. */
    List<UserRef> activeByRole(Role role);

    /** Active users of a role assigned to a vessel — its Ship Managers or Captain. */
    List<UserRef> activeOnVessel(Role role, Long vesselId);

    /**
     * Active users of a role for an organization — its Technical Head, or the
     * Service Coordinators assigned to serve it.
     */
    List<UserRef> activeForOrganization(Role role, Long organizationId);

    record UserRef(Long id, String fullName, String email, Role role, Long organizationId, boolean active) {}
}
