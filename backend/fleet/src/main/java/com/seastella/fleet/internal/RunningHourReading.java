package com.seastella.fleet.internal;

import com.seastella.core.api.model.BaseEntity;
import com.seastella.core.api.model.VesselScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One running-hour reading taken aboard (RHR-01).
 *
 * <p>Append-only: no setters. SOURCE-B s11 asks the platform to "retain
 * historical readings instead of overwriting previous values without
 * traceability", so a correction is a new reading, never an edit.
 */
@Entity
@Table(name = "running_hour_reading")
public class RunningHourReading extends BaseEntity implements VesselScoped {

    @Column(name = "spare_id", nullable = false)
    private Long spareId;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Column(name = "reading_hours", nullable = false, precision = 12, scale = 2)
    private BigDecimal readingHours;

    @Column(name = "previous_reading_hours", precision = 12, scale = 2)
    private BigDecimal previousReadingHours;

    @Column(name = "reading_date", nullable = false)
    private LocalDate readingDate;

    @Column(name = "recorded_by_user_id", nullable = false)
    private Long recordedByUserId;

    @Column(name = "note", length = 500)
    private String note;

    protected RunningHourReading() {
    }

    public RunningHourReading(Long spareId, Long vesselId, BigDecimal readingHours,
                              BigDecimal previousReadingHours, LocalDate readingDate,
                              Long recordedByUserId, String note) {
        this.spareId = spareId;
        this.vesselId = vesselId;
        this.readingHours = readingHours;
        this.previousReadingHours = previousReadingHours;
        this.readingDate = readingDate;
        this.recordedByUserId = recordedByUserId;
        this.note = note;
    }

    @Override
    public Long getVesselId() { return vesselId; }

    public Long getSpareId() { return spareId; }
    public BigDecimal getReadingHours() { return readingHours; }
    public BigDecimal getPreviousReadingHours() { return previousReadingHours; }
    public LocalDate getReadingDate() { return readingDate; }
    public Long getRecordedByUserId() { return recordedByUserId; }
    public String getNote() { return note; }
}
