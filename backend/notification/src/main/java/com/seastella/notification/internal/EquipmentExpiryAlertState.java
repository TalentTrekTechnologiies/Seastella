package com.seastella.notification.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The last expiry threshold announced for one piece of equipment, so a
 * reminder goes out when a threshold is crossed and not every night after it.
 *
 * <p>The expiry date is kept alongside the threshold on purpose. If somebody
 * corrects the date - a unit re-certified, a typo fixed - the stored threshold
 * describes a deadline that no longer exists, and the next sweep must be free
 * to announce the new one. Comparing both is what lets it tell "already told
 * them about this" from "this is a different deadline now".
 */
@Entity
@Table(name = "equipment_expiry_alert_state")
class EquipmentExpiryAlertState extends BaseEntity {

    @Column(name = "spare_id", nullable = false)
    private Long spareId;

    @Column(name = "last_threshold_days", nullable = false)
    private int lastThresholdDays;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "alerted_at", nullable = false)
    private Instant alertedAt;

    protected EquipmentExpiryAlertState() {
    }

    EquipmentExpiryAlertState(Long spareId, int lastThresholdDays, LocalDate expiryDate, Instant alertedAt) {
        this.spareId = spareId;
        this.lastThresholdDays = lastThresholdDays;
        this.expiryDate = expiryDate;
        this.alertedAt = alertedAt;
    }

    Long getSpareId() { return spareId; }
    int getLastThresholdDays() { return lastThresholdDays; }
    LocalDate getExpiryDate() { return expiryDate; }

    void announced(int thresholdDays, LocalDate expiry, Instant at) {
        this.lastThresholdDays = thresholdDays;
        this.expiryDate = expiry;
        this.alertedAt = at;
    }
}
