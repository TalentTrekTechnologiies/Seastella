package com.seastella.identity.api;

import java.util.Set;

/**
 * Lookups the scope resolver needs from modules that sit <em>above</em>
 * identity-access ({@code fleet} and {@code service-request}).
 *
 * <p>A port, declared here and implemented there, so the dependency still runs
 * downward and no cycle is introduced (docs/02 section 2.1).
 */
public interface VesselDirectory {

    Set<Long> vesselIdsForOrganization(Long organizationId);

    /** Service-request ids currently assigned to this engineer. */
    Set<Long> assignedJobIdsForEngineer(Long engineerUserId);

    /** Vessels reachable through the given jobs - and only through them. */
    Set<Long> vesselIdsForJobs(Set<Long> jobIds);
}
