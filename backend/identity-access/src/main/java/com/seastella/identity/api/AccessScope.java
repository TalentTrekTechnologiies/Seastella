package com.seastella.identity.api;

import java.util.Set;

/**
 * The resolved answer to "what may this principal see?", computed once per
 * request and consumed by every scoped query.
 *
 * @param kind           how the narrowing is applied
 * @param userId         the acting user
 * @param role           the acting role
 * @param organizationId owning organization; null for {@link ScopeKind#PLATFORM}
 * @param vesselIds      resolved vessel ids; empty for PLATFORM (meaning "all")
 * @param assignedJobIds service-request ids; only for {@link ScopeKind#JOB_SET}
 */
public record AccessScope(
        ScopeKind kind,
        Long userId,
        Role role,
        Long organizationId,
        Set<Long> vesselIds,
        Set<Long> assignedJobIds) {

    public AccessScope {
        vesselIds = vesselIds == null ? Set.of() : Set.copyOf(vesselIds);
        assignedJobIds = assignedJobIds == null ? Set.of() : Set.copyOf(assignedJobIds);
    }

    public boolean isPlatformWide() {
        return kind == ScopeKind.PLATFORM;
    }

    /** True when this principal may read data belonging to the given vessel. */
    public boolean permitsVessel(Long vesselId) {
        if (vesselId == null) return false;
        return switch (kind) {
            case PLATFORM -> true;
            case ORGANIZATION, VESSEL_SET -> vesselIds.contains(vesselId);
            // An engineer's vessel reach is derived from jobs, never asserted directly.
            case JOB_SET -> vesselIds.contains(vesselId);
        };
    }

    public boolean permitsOrganization(Long orgId) {
        if (kind == ScopeKind.PLATFORM) return true;
        return organizationId != null && organizationId.equals(orgId);
    }

    public boolean canSeeFinancials() {
        return role != null && role.canSeeFinancials();
    }
}
