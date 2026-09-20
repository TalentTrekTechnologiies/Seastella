package com.seastella.maintenance.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A configurable colour band (MNT-05).
 *
 * <p>The reference spec asks for these to be "configurable from the
 * Administration module rather than hard-coded", so they are rows, not
 * constants. {@code organizationId} null means the platform default; an
 * organization row overrides it.
 *
 * <p>Bands are stored as half-open day ranges to close the gap in the published
 * table, where "10-15" and "1-9" do not meet - see open item OI-02.
 */
@Entity
@Table(name = "maintenance_threshold")
public class MaintenanceThreshold extends BaseEntity {

    /** Null = platform default. */
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "status_code", nullable = false, length = 24)
    private String statusCode;

    /** Inclusive lower bound in days remaining. Null = unbounded below. */
    @Column(name = "min_days")
    private Integer minDays;

    /** Inclusive upper bound in days remaining. Null = unbounded above. */
    @Column(name = "max_days")
    private Integer maxDays;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected MaintenanceThreshold() {
    }

    public MaintenanceThreshold(Long organizationId, String statusCode, Integer minDays, Integer maxDays) {
        this.organizationId = organizationId;
        this.statusCode = statusCode;
        this.minDays = minDays;
        this.maxDays = maxDays;
    }

    public Long getOrganizationId() { return organizationId; }
    public String getStatusCode() { return statusCode; }
    public Integer getMinDays() { return minDays; }
    public Integer getMaxDays() { return maxDays; }
    public boolean isActive() { return active; }

    /** The Platform Admin moved a band's boundaries (SoW s11 configurable bands). */
    public void redefine(Integer minDays, Integer maxDays) {
        this.minDays = minDays;
        this.maxDays = maxDays;
    }

    public boolean matches(int daysRemaining) {
        if (minDays != null && daysRemaining < minDays) return false;
        if (maxDays != null && daysRemaining > maxDays) return false;
        return true;
    }
}
