package com.seastella.fleet.api;

/** Operational condition of a spare (reference spec section 8). */
public enum SpareStatus {
    OPERATIONAL,
    UNDER_MAINTENANCE,
    DEFECTIVE,
    OUT_OF_SERVICE
}
