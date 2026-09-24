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
}
