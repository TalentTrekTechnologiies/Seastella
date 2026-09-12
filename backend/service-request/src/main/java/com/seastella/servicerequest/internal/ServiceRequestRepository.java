package com.seastella.servicerequest.internal;

import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface ServiceRequestRepository extends JpaRepository<ServiceRequest, Long> {

    @Query("select r from ServiceRequest r where r.vesselId in :ids order by r.createdAt desc")
    Page<ServiceRequest> findByVessels(@Param("ids") Set<Long> vesselIds, Pageable pageable);

    @Query("select r from ServiceRequest r where r.vesselId in :ids and r.status in :statuses order by r.priority asc, r.createdAt asc")
    List<ServiceRequest> findByVesselsAndStatuses(@Param("ids") Set<Long> vesselIds,
                                                  @Param("statuses") Collection<ServiceRequestStatus> statuses);

    @Query("select r from ServiceRequest r where r.status in :statuses order by r.priority asc, r.createdAt asc")
    List<ServiceRequest> findByStatuses(@Param("statuses") Collection<ServiceRequestStatus> statuses);

    @Query("select r from ServiceRequest r where r.assignedEngineerUserId = :engineerId order by r.assignedAt desc")
    List<ServiceRequest> findByAssignedEngineer(@Param("engineerId") Long engineerUserId);

    @Query("select r.id from ServiceRequest r where r.assignedEngineerUserId = :engineerId")
    Set<Long> findIdsByAssignedEngineer(@Param("engineerId") Long engineerUserId);

    @Query("select distinct r.vesselId from ServiceRequest r where r.id in :ids")
    Set<Long> findVesselIdsByIds(@Param("ids") Set<Long> ids);

    // --- aggregates for dashboards: grouped in SQL, never by loading rows ---

    @Query("select r.status, count(r) from ServiceRequest r where r.vesselId in :ids group by r.status")
    List<Object[]> countByStatusForVessels(@Param("ids") Set<Long> vesselIds);

    @Query("select r.status, count(r) from ServiceRequest r group by r.status")
    List<Object[]> countByStatusPlatformWide();

    @Query("select r.priority, count(r) from ServiceRequest r where r.vesselId in :ids and r.closedAt is null group by r.priority")
    List<Object[]> countOpenByPriority(@Param("ids") Set<Long> vesselIds);

    @Query("select r.vesselId, count(r) from ServiceRequest r where r.vesselId in :ids and r.closedAt is null group by r.vesselId")
    List<Object[]> countOpenByVessel(@Param("ids") Set<Long> vesselIds);

    @Query("select count(r) from ServiceRequest r where r.vesselId in :ids and r.status in :statuses")
    long countByVesselsAndStatuses(@Param("ids") Set<Long> vesselIds,
                                   @Param("statuses") Collection<ServiceRequestStatus> statuses);

    @Query("select count(r) from ServiceRequest r where r.vesselId in :ids and r.resolutionType = 'ENGINEER_VISIT'")
    long countResolvedByEngineerVisit(@Param("ids") Set<Long> vesselIds);

    @Query("""
            select count(r) from ServiceRequest r
            where r.vesselId in :ids and r.status = 'CLOSED_NO_COST'
            """)
    long countResolvedWithoutCost(@Param("ids") Set<Long> vesselIds);

    /**
     * Raw (raisedAt, closedAt) pairs for closed requests, averaged by the
     * caller.
     *
     * <p>Deliberately not an aggregate: date arithmetic differs between
     * PostgreSQL and H2, and Hibernate 6 requires the temporal unit of
     * {@code timestampdiff} as a literal, so a portable JPQL average is not
     * available. Turnaround is a Coordinator dashboard figure over closed
     * requests for a handful of vessels, so the row count is small and the
     * averaging is done in Java rather than pinning the query to one engine.
     */
    @Query("""
            select r.createdAt, r.closedAt from ServiceRequest r
            where r.vesselId in :ids and r.closedAt is not null and r.createdAt >= :since
            """)
    List<Object[]> findClosedDurations(@Param("ids") Set<Long> vesselIds,
                                       @Param("since") Instant since);

    @Query("select r from ServiceRequest r where r.vesselId in :ids and r.createdAt >= :since order by r.createdAt desc")
    List<ServiceRequest> findRecent(@Param("ids") Set<Long> vesselIds, @Param("since") Instant since, Pageable pageable);

    boolean existsByRequestNumber(String requestNumber);

    @Query("select count(r) from ServiceRequest r where r.organizationId = :orgId")
    long countByOrganization(@Param("orgId") Long organizationId);
}
