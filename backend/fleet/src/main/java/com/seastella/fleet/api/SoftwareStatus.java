package com.seastella.fleet.api;

/**
 * How a unit's installed software compares with the latest release for its
 * model.
 *
 * <p>Decided on the server, like every other status this platform shows. The
 * browser is given the answer and draws it; it never compares two version
 * strings of its own, because a second implementation of
 * {@code SoftwareVersions} in TypeScript is a second set of answers.
 *
 * <p>Distinct from maintenance due-status: equipment can be perfectly in-date
 * for service and three releases behind, or freshly serviced by an engineer
 * who did not flash the firmware.
 */
public enum SoftwareStatus {

    /** Installed version matches the baseline. */
    CURRENT,

    /** The model has a newer release than the unit is running. */
    OUTDATED,

    /**
     * The unit is running something newer than the master sheet knows about -
     * normally a stale sheet, occasionally a unit flashed with a beta. Not an
     * error, but not silently "current" either: somebody should update the
     * sheet, and hiding it is how a sheet goes a year without maintenance.
     */
    AHEAD,

    /**
     * No comparison was possible: the unit has no version recorded, or no
     * baseline covers its model, or one of the two is not a version this
     * platform can order. Deliberately one value and not three - the fleet
     * list says "not known", and the detail belongs on the equipment itself.
     */
    UNKNOWN
}
