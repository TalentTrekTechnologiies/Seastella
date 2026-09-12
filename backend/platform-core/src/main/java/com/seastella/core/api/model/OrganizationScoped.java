package com.seastella.core.api.model;

/**
 * Implemented by every entity whose visibility is bounded by an organization
 * but not by a single vessel (the Organization record itself, org-level
 * configuration, and so on).
 *
 * @see VesselScoped
 */
public interface OrganizationScoped {

    /** Never null. The organization that owns this record. */
    Long getOrganizationId();
}
