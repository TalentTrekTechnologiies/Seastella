package com.seastella.servicerequest.api;

import java.util.Set;

/**
 * Read-only lookups other modules need about service requests, without reaching
 * into this module's entities.
 */
public interface ServiceRequestDirectory {

    /** Open request ids currently assigned to this engineer. */
    Set<Long> assignedJobIdsForEngineer(Long engineerUserId);

    /** Vessels reachable through the given jobs, and only through them. */
    Set<Long> vesselIdsForJobs(Set<Long> jobIds);

    /** The vessel a request belongs to, or null if unknown. */
    Long vesselIdFor(Long serviceRequestId);
}
