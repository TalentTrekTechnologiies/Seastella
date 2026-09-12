package com.seastella.maintenance.internal;

import com.seastella.core.api.model.BaseEntity;
import com.seastella.core.api.model.VesselScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A maintenance rule for one spare. A spare may carry both a calendar rule and
 * a running-hour rule; whichever falls due sooner decides the status (MNT-03).
 */
@Entity
@Table(name = "spare_maintenance_rule")
public class SpareMaintenanceRule extends BaseEntity implements VesselScoped {

    @Column(name = "spare_id", nullable = false)
    private Long spareId;

    /** Denormalised so the scope filter is a single-table predicate. */
    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Column(name = "rule_type", nullable = false, length = 24)
    private String ruleType;

    @Column(name = "interval_days")
    private Integer intervalDays;

    @Column(name = "interval_hours", precision = 12, scale = 2)
    private BigDecimal intervalHours;

    @Column(name = "last_service_date")
    private LocalDate lastServiceDate;

    @Column(name = "last_service_hours", precision = 12, scale = 2)
    private BigDecimal lastServiceHours;

    /** Written by the engine, never by hand. Indexed - every dashboard reads it. */
    @Column(name = "next_due_date")
    private LocalDate nextDueDate;

    @Column(name = "next_due_hours", precision = 12, scale = 2)
    private BigDecimal nextDueHours;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected SpareMaintenanceRule() {
    }

    public static SpareMaintenanceRule calendar(Long spareId, Long vesselId,
                                                int intervalDays, LocalDate lastServiceDate) {
        SpareMaintenanceRule r = new SpareMaintenanceRule();
        r.spareId = spareId;
        r.vesselId = vesselId;
        r.ruleType = "CALENDAR";
        r.intervalDays = intervalDays;
        r.lastServiceDate = lastServiceDate;
        r.nextDueDate = lastServiceDate == null ? null : lastServiceDate.plusDays(intervalDays);
        return r;
    }

    public static SpareMaintenanceRule runningHours(Long spareId, Long vesselId,
                                                    BigDecimal intervalHours, BigDecimal lastServiceHours) {
        SpareMaintenanceRule r = new SpareMaintenanceRule();
        r.spareId = spareId;
        r.vesselId = vesselId;
        r.ruleType = "RUNNING_HOURS";
        r.intervalHours = intervalHours;
        r.lastServiceHours = lastServiceHours;
        r.nextDueHours = lastServiceHours == null ? intervalHours : lastServiceHours.add(intervalHours);
        return r;
    }

    @Override
    public Long getVesselId() { return vesselId; }

    public Long getSpareId() { return spareId; }
    public String getRuleType() { return ruleType; }
    public Integer getIntervalDays() { return intervalDays; }
    public BigDecimal getIntervalHours() { return intervalHours; }
    public LocalDate getLastServiceDate() { return lastServiceDate; }
    public BigDecimal getLastServiceHours() { return lastServiceHours; }
    public LocalDate getNextDueDate() { return nextDueDate; }
    public BigDecimal getNextDueHours() { return nextDueHours; }
    public boolean isActive() { return active; }
    public boolean isCalendar() { return "CALENDAR".equals(ruleType); }

    /** Resets the cycle after a completed service (MNT-10). */
    public void completeService(LocalDate serviceDate, BigDecimal serviceHours) {
        if (isCalendar() && intervalDays != null) {
            this.lastServiceDate = serviceDate;
            this.nextDueDate = serviceDate.plusDays(intervalDays);
        } else if (intervalHours != null && serviceHours != null) {
            this.lastServiceHours = serviceHours;
            this.nextDueHours = serviceHours.add(intervalHours);
        }
    }

    public void setActive(boolean active) { this.active = active; }
}
