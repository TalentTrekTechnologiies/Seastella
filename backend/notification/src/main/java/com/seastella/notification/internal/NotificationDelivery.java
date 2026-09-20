package com.seastella.notification.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An attempt to deliver a notification outside the app - today, by email.
 *
 * <p>Kept per notification so the Platform Admin can see what was sent, what
 * failed and why (SoW s8.5 "system status"). A channel added later - SMS, if
 * Seastella confirms it - is another row with another channel value.
 */
@Entity
@Table(name = "notification_delivery")
class NotificationDelivery extends BaseEntity {

    static final String EMAIL = "EMAIL";

    static final String PENDING = "PENDING";
    static final String SENT = "SENT";
    static final String FAILED = "FAILED";
    static final String SKIPPED = "SKIPPED";

    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    @Column(name = "channel", nullable = false, length = 16)
    private String channel;

    @Column(name = "address", nullable = false, length = 254)
    private String address;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected NotificationDelivery() {
    }

    NotificationDelivery(Long notificationId, String channel, String address) {
        this.notificationId = notificationId;
        this.channel = channel;
        this.address = address;
        this.status = PENDING;
    }

    Long getNotificationId() { return notificationId; }
    String getChannel() { return channel; }
    String getAddress() { return address; }
    String getStatus() { return status; }
    int getAttempts() { return attempts; }
    String getLastError() { return lastError; }
    Instant getSentAt() { return sentAt; }

    void sent(Instant at) {
        attempts++;
        status = SENT;
        sentAt = at;
        lastError = null;
    }

    void skipped(String reason) {
        status = SKIPPED;
        lastError = reason;
    }

    /** Stays pending for another try until the attempts run out. */
    void failed(String error, int maxAttempts) {
        attempts++;
        lastError = error == null ? null : error.length() > 500 ? error.substring(0, 500) : error;
        status = attempts >= maxAttempts ? FAILED : PENDING;
    }
}
