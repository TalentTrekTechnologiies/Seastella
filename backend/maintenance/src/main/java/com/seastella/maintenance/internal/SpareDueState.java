package com.seastella.maintenance.internal;

import com.seastella.core.api.model.BaseEntity;
import com.seastella.core.api.model.VesselScoped;
import com.seastella.maintenance.api.DueStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The colour status the monitor last observed for a spare. Used only to tell a
 * change from a repeat, so an overdue spare alerts once, not every night.
 */
@Entity
@Table(name = "spare_due_state")
class SpareDueState extends BaseEntity implements VesselScoped {

    @Column(name = "spare_id", nullable = false)
    private Long spareId;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private DueStatus status;

    @Column(name = "next_due_date")
    private LocalDate nextDueDate;

    @Column(name = "basis", length = 24)
    private String basis;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    protected SpareDueState() {
    }

    SpareDueState(Long spareId, Long vesselId) {
        this.spareId = spareId;
        this.vesselId = vesselId;
        this.status = DueStatus.NOT_TRACKED;
        this.evaluatedAt = Instant.now();
    }

    @Override
    public Long getVesselId() { return vesselId; }

    Long getSpareId() { return spareId; }
    DueStatus getStatus() { return status; }
    LocalDate getNextDueDate() { return nextDueDate; }

    void observe(DueStatus status, LocalDate nextDueDate, String basis, Instant at) {
        this.status = status;
        this.nextDueDate = nextDueDate;
        this.basis = basis;
        this.evaluatedAt = at;
    }
}
