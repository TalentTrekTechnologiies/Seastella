package com.seastella.fleet.api;

import java.util.List;

/**
 * What the master-sheet import needs from {@code fleet}: read the software
 * baselines that exist, and write the ones an administrator confirmed.
 *
 * <p>A baseline names the latest release for an equipment model. It is
 * platform-wide master data, not vessel data - every Furuno FA-170 in every
 * fleet is measured against the same version - which is why nothing here takes
 * a vessel.
 */
public interface SoftwareBaselineGateway {

    List<Baseline> all();

    /**
     * Writes a sheet's rows.
     *
     * <p>A row whose model already has a baseline updates it; a model not seen
     * before is added. A baseline somebody typed by hand
     * ({@link Origin#RECORDED}) is <em>not</em> overwritten by a sheet, because
     * a hand correction is normally the more recent knowledge - the person
     * fixing it had the release note in front of them. Those are reported back
     * as skipped rather than silently ignored.
     *
     * @return what the write did, row by row, for the confirmation screen
     */
    UpsertResult upsertFromSheet(List<Baseline> rows);

    /**
     * Every unit running something older than its model's baseline, fleet-wide.
     *
     * <p>Read as a whole rather than per vessel: the sweep that uses it runs
     * nightly over everything, and the table it reads is one row per model -
     * hundreds - against equipment counted in thousands, so a query per vessel
     * would be the same work split into more round trips.
     *
     * <p>Only OUTDATED is returned. A unit ahead of the sheet is a stale sheet
     * and belongs to whoever maintains it, not to the vessel; a unit with no
     * version recorded is an empty cell, not a finding.
     */
    List<OutdatedUnit> outdated();

    /** A unit that is behind, with both versions and where to find it. */
    record OutdatedUnit(Long spareId, String name, String path, Long vesselId, String vesselName,
                        Long organizationId, String installed, String latest) {}

    /** A model's baseline as this platform holds it. */
    record Baseline(Long id, String make, String model, String equipmentName,
                    String latestVersion, Origin origin, String notes) {

        /** A sheet row, before it has an id or an origin of its own. */
        public static Baseline fromSheet(String make, String model, String equipmentName,
                                         String latestVersion) {
            return new Baseline(null, make, model, equipmentName, latestVersion, Origin.IMPORTED, null);
        }
    }

    enum Origin { IMPORTED, RECORDED }

    /**
     * @param added    models the sheet introduced
     * @param updated  models whose version the sheet moved
     * @param unchanged models the sheet repeated without changing
     * @param skipped  hand-entered baselines the sheet did not overwrite, and why
     */
    record UpsertResult(int added, int updated, int unchanged, List<Skipped> skipped) {}

    record Skipped(String make, String model, String reason) {}
}
