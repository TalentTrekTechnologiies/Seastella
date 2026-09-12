package com.seastella.maintenance.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MaintenanceThresholdRepository extends JpaRepository<MaintenanceThreshold, Long> {

    /**
     * Organization overrides first, then platform defaults. The engine takes the
     * first match, so ordering is the override mechanism.
     */
    @Query("""
            select t from MaintenanceThreshold t
            where t.active = true
              and (t.organizationId = :orgId or t.organizationId is null)
            order by case when t.organizationId is null then 1 else 0 end
            """)
    List<MaintenanceThreshold> findApplicable(@Param("orgId") Long orgId);
}
