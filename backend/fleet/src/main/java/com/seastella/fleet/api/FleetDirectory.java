package com.seastella.fleet.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    /** The SoW s9.4 equipment categories, in display order. */
    List<CategoryRef> equipmentCategories();

    /** The owning organization's short code, used in request and invoice numbers. */
    Optional<String> organizationCodeForVessel(Long vesselId);

    boolean organizationExists(Long organizationId);

    /** A spare's identity and hour meter, for labels, alerts and due projections. */
    Optional<SpareRef> spareRef(Long spareId);

    /**
     * Equipment on one vessel that carries an expiry date of its own, soonest
     * first.
     *
     * <p>Distinct from a certificate expiry, which belongs to a document, and
     * from a maintenance due date, which is a recurring service falling due.
     * This is the date the unit itself stops being fit for use - a life-limited
     * battery, a hydrostatic release, a liferaft bottle - after which no amount
     * of servicing makes it compliant.
     */
    List<EquipmentExpiry> equipmentExpiries(Long vesselId);

    /**
     * Equipment anywhere the caller may see, expiring on or before {@code cutoff},
     * soonest first. Used by the nightly reminder sweep, which bounds it by the
     * widest warning it sends.
     */
    List<EquipmentExpiry> equipmentExpiringBy(LocalDate cutoff);

    /** Recorded running-hour readings, most recent first. */
    List<HourReading> hourReadings(Long spareId, int limit);

    record SpareRef(Long id, String name, String path, Long vesselId, String vesselName,
                    Long organizationId, boolean tracksRunningHours, BigDecimal runningHours) {}

    /** A unit and the day it stops being fit for use, with where to find it. */
    record EquipmentExpiry(Long spareId, String name, String path, LocalDate expiryDate,
                           Long vesselId, String vesselName, Long organizationId) {}

    record HourReading(LocalDate readingDate, BigDecimal hours) {}

    record CategoryRef(Long id, String code, String name) {}
}
