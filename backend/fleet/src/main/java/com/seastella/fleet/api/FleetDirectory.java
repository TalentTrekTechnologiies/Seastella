package com.seastella.fleet.api;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fleet lookups other modules may use.
 *
 * <p>The published surface over {@code fleet}'s internals: nothing outside this
 * module touches {@code VesselRepository} or {@code SpareRepository} directly
 * (docs/02 s2.1 rule 1).
 */
public interface FleetDirectory {

    Set<Long> vesselIdsForOrganization(Long organizationId);

    Long organizationIdForVessel(Long vesselId);

    Long organizationIdForSpare(Long spareId);

    Long vesselIdForSpare(Long spareId);

    /**
     * Spare id to "accrues running hours", for one vessel.
     *
     * <p>Used by the maintenance module to attach rules without reaching into
     * fleet's repositories.
     */
    Map<Long, Boolean> spareIdsWithHourTracking(Long vesselId);

    /** Resolve an equipment category by its code, e.g. {@code "ECDIS"}. */
    Optional<Long> equipmentCategoryIdByCode(String code);
}
