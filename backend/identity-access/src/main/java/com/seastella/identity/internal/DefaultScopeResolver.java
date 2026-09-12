package com.seastella.identity.internal;

import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeKind;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.identity.api.VesselDirectory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.annotation.RequestScope;

import java.util.Set;

/**
 * Resolves the caller's scope once per request and caches it for that request.
 *
 * <p>Request-scoped deliberately: resolving per call would issue the same
 * vessel-assignment query several times inside one request, and caching it
 * longer would mean a vessel re-allocation did not take effect until the user
 * signed out.
 */
@Service
@RequestScope
class DefaultScopeResolver implements ScopeResolver {

    private final UserVesselAssignmentRepository assignments;
    private final VesselDirectory vesselDirectory;

    private AccessScope cached;

    DefaultScopeResolver(UserVesselAssignmentRepository assignments, VesselDirectory vesselDirectory) {
        this.assignments = assignments;
        this.vesselDirectory = vesselDirectory;
    }

    @Override
    public AccessScope currentScope() {
        if (cached != null) {
            return cached;
        }
        cached = resolve();
        return cached;
    }

    private AccessScope resolve() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof SeaStellaPrincipal p)) {
            // Anonymous: an empty VESSEL_SET, which permits nothing.
            return new AccessScope(ScopeKind.VESSEL_SET, null, null, null, Set.of(), Set.of());
        }

        Role role = p.role();
        Long orgId = p.organizationId();

        return switch (role.scopeKind()) {
            case PLATFORM -> new AccessScope(
                    ScopeKind.PLATFORM, p.userId(), role, null, Set.of(), Set.of());

            case ORGANIZATION -> new AccessScope(
                    ScopeKind.ORGANIZATION, p.userId(), role, orgId,
                    vesselDirectory.vesselIdsForOrganization(orgId), Set.of());

            case VESSEL_SET -> new AccessScope(
                    ScopeKind.VESSEL_SET, p.userId(), role, orgId,
                    assignments.findVesselIdsByUserId(p.userId()), Set.of());

            case JOB_SET -> {
                // An engineer's vessel reach is derived from assigned jobs only.
                Set<Long> jobIds = vesselDirectory.assignedJobIdsForEngineer(p.userId());
                Set<Long> vesselIds = vesselDirectory.vesselIdsForJobs(jobIds);
                yield new AccessScope(
                        ScopeKind.JOB_SET, p.userId(), role, orgId, vesselIds, jobIds);
            }
        };
    }
}
