package com.seastella.fleet.api;

/** Vessel operational status (SoW section 8.1). */
public enum VesselStatus {
    ACTIVE("Active"),
    DRY_DOCK("Dry dock"),
    INACTIVE("Inactive"),
    DECOMMISSIONED("Decommissioned");

    private final String label;

    VesselStatus(String label) { this.label = label; }

    public String label() { return label; }
}
