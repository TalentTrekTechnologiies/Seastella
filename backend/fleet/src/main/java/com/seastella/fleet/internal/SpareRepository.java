package com.seastella.fleet.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface SpareRepository extends JpaRepository<Spare, Long> {

    List<Spare> findByVesselIdOrderByPathAsc(Long vesselId);

    List<Spare> findByVesselIdInOrderByPathAsc(Set<Long> vesselIds);

    List<Spare> findByParentSpareId(Long parentSpareId);

    /**
     * A whole subtree in one indexed read, using the materialised path rather
     * than a recursive walk.
     */
    @Query("select s from Spare s where s.vesselId = :vesselId and s.path like concat(:path, '.%') order by s.path")
    List<Spare> findDescendants(@Param("vesselId") Long vesselId, @Param("path") String path);

    long countByVesselIdIn(Set<Long> vesselIds);

    @Query("select s.criticality, count(s) from Spare s where s.vesselId in :ids group by s.criticality")
    List<Object[]> countByCriticality(@Param("ids") Set<Long> ids);

    @Query("select s.equipmentCategoryId, count(s) from Spare s where s.vesselId in :ids group by s.equipmentCategoryId")
    List<Object[]> countByCategory(@Param("ids") Set<Long> ids);

    @Query("select s.vesselId, count(s) from Spare s where s.vesselId in :ids group by s.vesselId")
    List<Object[]> countByVessel(@Param("ids") Set<Long> ids);
}
