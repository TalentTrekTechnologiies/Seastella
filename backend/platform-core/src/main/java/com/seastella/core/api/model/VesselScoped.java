package com.seastella.core.api.model;

/**
 * Implemented by every entity whose visibility is bounded by a vessel.
 *
 * <p>This is the contract the scope filter keys on. An entity that holds
 * vessel-owned data and does <em>not</em> implement this interface is invisible
 * to the isolation machinery - which is exactly the bug class that
 * docs/04-rbac-and-scope.md exists to prevent - so
 * {@code ScopedEntityCoverageTest} fails the build for any entity carrying a
 * {@code vessel_id} column without it.
 */
public interface VesselScoped {

    /** Never null. The vessel that owns this record. */
    Long getVesselId();
}
