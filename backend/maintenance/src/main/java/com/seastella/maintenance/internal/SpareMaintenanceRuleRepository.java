package com.seastella.maintenance.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface SpareMaintenanceRuleRepository extends JpaRepository<SpareMaintenanceRule, Long> {

    List<SpareMaintenanceRule> findBySpareIdAndActiveTrue(Long spareId);

    List<SpareMaintenanceRule> findByVesselIdInAndActiveTrue(Set<Long> vesselIds);

    @Query("select distinct r.spareId from SpareMaintenanceRule r where r.active = true")
    List<Long> findActiveSpareIds();

    @Query("""
            select r from SpareMaintenanceRule r
            where r.active = true and r.vesselId in :ids
              and r.nextDueDate is not null and r.nextDueDate <= :cutoff
            order by r.nextDueDate asc
            """)
    List<SpareMaintenanceRule> findDueBefore(@Param("ids") Set<Long> vesselIds,
                                             @Param("cutoff") LocalDate cutoff);

    @Query("""
            select count(r) from SpareMaintenanceRule r
            where r.active = true and r.vesselId in :ids
              and r.nextDueDate is not null and r.nextDueDate < :today
            """)
    long countOverdue(@Param("ids") Set<Long> vesselIds, @Param("today") LocalDate today);

    @Query("""
            select count(r) from SpareMaintenanceRule r
            where r.active = true and r.vesselId in :ids
              and r.nextDueDate is not null
              and r.nextDueDate >= :today and r.nextDueDate <= :cutoff
            """)
    long countDueSoon(@Param("ids") Set<Long> vesselIds,
                      @Param("today") LocalDate today,
                      @Param("cutoff") LocalDate cutoff);
}
