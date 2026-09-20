package com.seastella.servicerequest.internal;

import com.seastella.fleet.api.FleetDirectory;
import com.seastella.identity.api.VesselDirectory;
import com.seastella.servicerequest.api.ServiceRequestDirectory;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Supplies the scope resolver with what it needs from the modules above
 * identity-access.
 *
 * <p>Lives in {@code service-request} because that is where job assignment is
 * known; it reaches {@code fleet} only through {@link FleetDirectory}, never
 * through fleet's repositories.
 *
 * <p>Note what {@link #vesselIdsForJobs} does <em>not</em> do: it never widens
 * an engineer's reach to every vessel in an organization. The vessels it
 * returns are exactly those carrying one of the engineer's own jobs.
 */
@Component
class DefaultVesselDirectory implements VesselDirectory, ServiceRequestDirectory {

    /** Statuses at which an engineer still has work to do on a job. */
    private static final Set<ServiceRequestStatus> ENGINEER_ACTIVE = Set.of(
            ServiceRequestStatus.ENGINEER_ASSIGNED,
            ServiceRequestStatus.IN_PROGRESS,
            ServiceRequestStatus.COMPLETION_REPORTED,
            ServiceRequestStatus.COMPLETED);

    private final FleetDirectory fleet;
    private final ServiceRequestRepository requests;

    DefaultVesselDirectory(FleetDirectory fleet, ServiceRequestRepository requests) {
        this.fleet = fleet;
        this.requests = requests;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> vesselIdsForOrganization(Long organizationId) {
        return fleet.vesselIdsForOrganization(organizationId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> assignedJobIdsForEngineer(Long engineerUserId) {
        if (engineerUserId == null) {
            return Set.of();
        }
        return requests.findByAssignedEngineer(engineerUserId).stream()
                .filter(r -> ENGINEER_ACTIVE.contains(r.getStatus()))
                .map(ServiceRequest::getId)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> vesselIdsForJobs(Set<Long> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) {
            return Set.of();
        }
        return requests.findVesselIdsByIds(jobIds);
    }

    @Override
    @Transactional(readOnly = true)
    public Long organizationIdForVessel(Long vesselId) {
        return fleet.organizationIdForVessel(vesselId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean organizationExists(Long organizationId) {
        return fleet.organizationExists(organizationId);
    }

    @Override
    @Transactional(readOnly = true)
    public Long vesselIdFor(Long serviceRequestId) {
        if (serviceRequestId == null) return null;
        return requests.findById(serviceRequestId)
                .map(ServiceRequest::getVesselId)
                .orElse(null);
    }
}
