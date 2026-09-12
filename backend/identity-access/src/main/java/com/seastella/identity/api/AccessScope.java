package com.seastella.identity.api;

import java.util.Set;

/**
 * The resolved answer to "what may this principal see?", computed once per
 * request and consumed by every scoped query.
 *
 * @param kind            how the narrowing is applied
 * @param userId          the acting user
 * @param role            the acting role
 * @param organizationId  the single owning organization for client-tenant
 *                        roles; null for PLATFORM, ORGANIZATION_SET and JOB_SET
 * @param organizationIds every organization this principal may reach. For
 *                        ORGANIZATION it holds the one; for ORGANIZATION_SET
 *                        the assigned set; empty for PLATFORM (meaning "all")
 * @param vesselIds       resolved vessel ids; empty for PLATFORM
 * @param assignedJobIds  service-request ids; only for {@link ScopeKind#JOB_SET}
 */
public record AccessScope(
        ScopeKind kind,
        Long userId,
        Role role,
        Long organizationId,
        Set<Long> organizationIds,
        Set<Long> vesselIds,
        Set<Long> assignedJobIds) {

    public AccessScope {
        organizationIds = organizationIds == null ? Set.of() : Set.copyOf(organizationIds);
        vesselIds = vesselIds == null ? Set.of() : Set.copyOf(vesselIds);
        assignedJobIds = assignedJobIds == null ? Set.of() : Set.copyOf(assignedJobIds);
    }

    /** Convenience for the single-organization roles. */
    public static AccessScope ofOrganization(Long userId, Role role, Long organizationId,
                                             Set<Long> vesselIds) {
        return new AccessScope(ScopeKind.ORGANIZATION, userId, role, organizationId,
                organizationId == null ? Set.of() : Set.of(organizationId), vesselIds, Set.of());
    }

    public static AccessScope ofVessels(Long userId, Role role, Long organizationId,
                                        Set<Long> vesselIds) {
        return new AccessScope(ScopeKind.VESSEL_SET, userId, role, organizationId,
                organizationId == null ? Set.of() : Set.of(organizationId), vesselIds, Set.of());
    }

    public static AccessScope ofOrganizations(Long userId, Role role, Set<Long> organizationIds,
                                              Set<Long> vesselIds) {
        return new AccessScope(ScopeKind.ORGANIZATION_SET, userId, role, null,
                organizationIds, vesselIds, Set.of());
    }

    public static AccessScope ofJobs(Long userId, Role role, Set<Long> jobIds,
                                     Set<Long> vesselIds) {
        return new AccessScope(ScopeKind.JOB_SET, userId, role, null, Set.of(),
                vesselIds, jobIds);
    }

    public static AccessScope ofPlatform(Long userId, Role role) {
        return new AccessScope(ScopeKind.PLATFORM, userId, role, null,
                Set.of(), Set.of(), Set.of());
    }

    /** Anonymous: an empty vessel set, which permits nothing. */
    public static AccessScope none() {
        return new AccessScope(ScopeKind.VESSEL_SET, null, null, null,
                Set.of(), Set.of(), Set.of());
    }

    public boolean isPlatformWide() {
        return kind == ScopeKind.PLATFORM;
    }

    /** True when this principal may read data belonging to the given vessel. */
    public boolean permitsVessel(Long vesselId) {
        if (vesselId == null) return false;
        return switch (kind) {
            case PLATFORM -> true;
            // For every narrowed kind the vessel set is already the resolved
            // answer, including JOB_SET, where it was derived from jobs alone.
            case ORGANIZATION, ORGANIZATION_SET, VESSEL_SET, JOB_SET -> vesselIds.contains(vesselId);
        };
    }

    public boolean permitsOrganization(Long orgId) {
        if (kind == ScopeKind.PLATFORM) return true;
        if (orgId == null) return false;
        return organizationIds.contains(orgId);
    }

    /** True when this principal is authorised for more than one organization. */
    public boolean isMultiOrganization() {
        return kind == ScopeKind.ORGANIZATION_SET && organizationIds.size() > 1;
    }

    public boolean canSeeFinancials() {
        return role != null && role.canSeeFinancials();
    }
}
