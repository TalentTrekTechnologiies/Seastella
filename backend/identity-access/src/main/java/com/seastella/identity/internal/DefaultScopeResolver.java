package com.seastella.identity.internal;

import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.identity.api.VesselDirectory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.annotation.RequestScope;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Resolves the caller's scope once per request and caches it for that request.
 *
 * <p>Request-scoped deliberately: resolving per call would issue the same
 * assignment queries several times inside one request, and caching it longer
 * would mean a vessel re-allocation did not take effect until the user signed
 * out.
 *
 * <p><b>Nothing here reads a request parameter.</b> Every set below is derived
 * from the authenticated principal's own assignment rows, so a caller cannot
 * widen their own scope by supplying an organization or vessel id.
 */
@Service
@RequestScope
class DefaultScopeResolver implements ScopeResolver {

    private final UserVesselAssignmentRepository vesselAssignments;
    private final UserOrganizationAssignmentRepository organizationAssignments;
    private final VesselDirectory vesselDirectory;

    private AccessScope cached;

    DefaultScopeResolver(UserVesselAssignmentRepository vesselAssignments,
                         UserOrganizationAssignmentRepository organizationAssignments,
                         VesselDirectory vesselDirectory) {
        this.vesselAssignments = vesselAssignments;
        this.organizationAssignments = organizationAssignments;
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
            return AccessScope.none();
        }

        Role role = p.role();
        Long orgId = p.organizationId();

        return switch (role.scopeKind()) {
            case PLATFORM -> AccessScope.ofPlatform(p.userId(), role);

            case ORGANIZATION -> AccessScope.ofOrganization(p.userId(), role, orgId,
                    vesselDirectory.vesselIdsForOrganization(orgId));

            case ORGANIZATION_SET -> {
                // OI-16: Seastella service staff, assigned to a set of client
                // organizations. The vessel set is the union of those
                // organizations' fleets - and nothing outside them.
                Set<Long> orgIds = organizationAssignments.findOrganizationIdsByUserId(p.userId());

                Set<Long> vesselIds = new LinkedHashSet<>();
                for (Long id : orgIds) {
                    vesselIds.addAll(vesselDirectory.vesselIdsForOrganization(id));
                }
                yield AccessScope.ofOrganizations(p.userId(), role, orgIds, vesselIds);
            }

            case VESSEL_SET -> AccessScope.ofVessels(p.userId(), role, orgId,
                    vesselAssignments.findVesselIdsByUserId(p.userId()));

            case JOB_SET -> {
                // An engineer's vessel reach is derived from assigned jobs only.
                Set<Long> jobIds = vesselDirectory.assignedJobIdsForEngineer(p.userId());
                yield AccessScope.ofJobs(p.userId(), role, jobIds,
                        vesselDirectory.vesselIdsForJobs(jobIds));
            }
        };
    }
}
