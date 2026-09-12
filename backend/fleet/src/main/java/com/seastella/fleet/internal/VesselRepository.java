package com.seastella.fleet.internal;

import com.seastella.fleet.api.VesselStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface VesselRepository extends JpaRepository<Vessel, Long> {

    List<Vessel> findByOrganizationId(Long organizationId);

    List<Vessel> findByIdIn(Set<Long> ids);

    @Query("select v.id from Vessel v where v.organizationId = :orgId")
    Set<Long> findIdsByOrganizationId(@Param("orgId") Long orgId);

    @Query("select v.status, count(v) from Vessel v where v.id in :ids group by v.status")
    List<Object[]> countByStatusForVessels(@Param("ids") Set<Long> ids);

    @Query("select v.status, count(v) from Vessel v group by v.status")
    List<Object[]> countByStatusPlatformWide();

    long countByOrganizationId(Long organizationId);

    boolean existsByImoNumber(String imoNumber);
}
