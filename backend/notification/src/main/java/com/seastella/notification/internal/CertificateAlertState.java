package com.seastella.notification.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The last expiry threshold announced for one certificate, so a reminder is
 * sent when a threshold is crossed and not every night after it.
 */
@Entity
@Table(name = "certificate_alert_state")
class CertificateAlertState extends BaseEntity {

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "last_threshold_days", nullable = false)
    private int lastThresholdDays;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "alerted_at", nullable = false)
    private Instant alertedAt;

    protected CertificateAlertState() {
    }

    CertificateAlertState(Long documentId, int lastThresholdDays, LocalDate expiryDate, Instant alertedAt) {
        this.documentId = documentId;
        this.lastThresholdDays = lastThresholdDays;
        this.expiryDate = expiryDate;
        this.alertedAt = alertedAt;
    }

    Long getDocumentId() { return documentId; }
    int getLastThresholdDays() { return lastThresholdDays; }
    LocalDate getExpiryDate() { return expiryDate; }

    void announced(int thresholdDays, LocalDate expiry, Instant at) {
        this.lastThresholdDays = thresholdDays;
        this.expiryDate = expiry;
        this.alertedAt = at;
    }
}
