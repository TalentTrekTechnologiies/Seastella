package com.seastella.fleet.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface ReplacementPartRepository extends JpaRepository<ReplacementPart, Long> {

    List<ReplacementPart> findByVesselIdIn(Set<Long> vesselIds);

    /** Shortage is derived in the predicate, never read from a stored flag. */
    @Query("select p from ReplacementPart p where p.vesselId in :ids and p.quantityOnHand < p.minimumQuantity")
    List<ReplacementPart> findBelowMinimum(@Param("ids") Set<Long> ids);

    @Query("select count(p) from ReplacementPart p where p.vesselId in :ids and p.quantityOnHand < p.minimumQuantity")
    long countBelowMinimum(@Param("ids") Set<Long> ids);
}
