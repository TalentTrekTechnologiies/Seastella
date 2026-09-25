package com.seastella.fleet.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SpareServiceRecordRepository extends JpaRepository<SpareServiceRecord, Long> {

    List<SpareServiceRecord> findBySpareIdOrderByServiceDateDescIdDesc(Long spareId);

    Optional<SpareServiceRecord> findByServiceRequestId(Long serviceRequestId);

    /** The newest service date on a spare — what the maintenance cycle runs from. */
    @Query("select max(r.serviceDate) from SpareServiceRecord r where r.spareId = :spareId")
    LocalDate newestServiceDate(Long spareId);

    long countBySpareId(Long spareId);

    /** The fleet-wide history, newest first. */
    List<SpareServiceRecord> findByVesselIdInOrderByServiceDateDescIdDesc(java.util.Set<Long> vesselIds,
                                                                          org.springframework.data.domain.Pageable page);

    List<SpareServiceRecord> findAllByOrderByServiceDateDescIdDesc(org.springframework.data.domain.Pageable page);

    /** The same work on the same day is the same entry: an upload repeated does not double it. */
    boolean existsBySpareIdAndServiceDateAndWorkPerformedIgnoreCase(Long spareId, LocalDate serviceDate,
                                                                     String workPerformed);
}
