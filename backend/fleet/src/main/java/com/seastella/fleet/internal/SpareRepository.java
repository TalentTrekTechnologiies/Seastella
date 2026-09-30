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

    /** Any top-level spare already filed under a category, for its VMP block number. */
    java.util.Optional<Spare> findFirstByEquipmentCategoryIdAndParentSpareIdIsNullOrderByIdAsc(Long categoryId);

    /** Every top-level VMP number in use, so a new category's block does not collide with one. */
    @Query("select s.path from Spare s where s.parentSpareId is null")
    List<String> topLevelPaths();

    /**
     * A whole subtree in one indexed read, using the materialised path rather
     * than a recursive walk.
     */
    @Query("select s from Spare s where s.vesselId = :vesselId and s.path like concat(:path, '.%') order by s.path")
    List<Spare> findDescendants(@Param("vesselId") Long vesselId, @Param("path") String path);

    /**
     * Equipment on one vessel that carries an expiry date of its own, soonest
     * first. Equipment without one is left out rather than returned null-dated:
     * "no expiry recorded" is the ordinary state of most components, and a
     * caller counting rows should not be counting those.
     */
    List<Spare> findByVesselIdAndExpirationDateIsNotNullOrderByExpirationDateAsc(Long vesselId);

    /**
     * Equipment anywhere in the fleet expiring on or before a day, soonest
     * first - the nightly reminder sweep. Bounded by the widest warning the
     * platform sends, so it never reads the whole table.
     */
    List<Spare> findByExpirationDateLessThanEqualOrderByExpirationDateAsc(java.time.LocalDate cutoff);

    long countByVesselIdIn(Set<Long> vesselIds);

    @Query("select s.criticality, count(s) from Spare s where s.vesselId in :ids group by s.criticality")
    List<Object[]> countByCriticality(@Param("ids") Set<Long> ids);

    @Query("select s.equipmentCategoryId, count(s) from Spare s where s.vesselId in :ids group by s.equipmentCategoryId")
    List<Object[]> countByCategory(@Param("ids") Set<Long> ids);

    @Query("select s.vesselId, count(s) from Spare s where s.vesselId in :ids group by s.vesselId")
    List<Object[]> countByVessel(@Param("ids") Set<Long> ids);
}
