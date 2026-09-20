package com.seastella.fleet.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the VMP import needs from {@code fleet}: resolve a vessel by IMO, read
 * the spares it already has, and write the rows an administrator confirmed.
 *
 * <p>Writes here are deliberately narrow. A spare's <em>identity and facts</em>
 * are master data and may be imported; its running hours and working status are
 * recorded on board as work happens and are never overwritten by a spreadsheet
 * (IMP-12). Changing a last annual service date still publishes
 * {@link FleetEvents.ServiceDateChanged}, so maintenance tracking reacts to an
 * import exactly as it does to an edit.
 */
public interface SpareImportGateway {

    /** The vessel a row's IMO names, whatever organization it belongs to; the caller checks scope. */
    Optional<VesselRef> vesselByImo(String imoNumber);

    Optional<VesselRef> vessel(Long vesselId);

    /** Every spare on the vessel, keyed by its VMP reference - the import's match key. */
    Map<String, ExistingSpare> sparesByVmpRef(Long vesselId);

    /** VMP references already on the vessel, for parent lookups while previewing. */
    List<String> vmpRefs(Long vesselId);

    /** Adds a spare. The parent is resolved from the decimal reference, e.g. 13.1.2 sits under 13.1. */
    Long create(Long vesselId, String vmpRef, Long equipmentCategoryId, SpareValues values);

    /**
     * Applies only the values present; a null field means "leave as it is".
     * A non-null {@code equipmentCategoryId} moves the spare to that category.
     */
    void update(Long spareId, Long equipmentCategoryId, SpareValues values);

    record VesselRef(Long id, String name, String imoNumber, Long organizationId) {}

    record ExistingSpare(Long id, String vmpRef, Long equipmentCategoryId, String categoryCode, SpareValues values) {}

    /**
     * The importable facts of a spare. Every field is optional: a blank cell in
     * the sheet means "leave this as it is", so a partial sheet never wipes
     * details somebody entered by hand.
     */
    record SpareValues(String name, String make, String model, String serialNumber, String softwareVersion,
                       LocalDate installationDate, LocalDate expirationDate, LocalDate lastAnnualServiceDate,
                       LocalDate lastSurveyDate, LocalDate lastAptDate, Boolean tracksRunningHours,
                       String criticality) {

        public static SpareValues empty() {
            return new SpareValues(null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }
}
