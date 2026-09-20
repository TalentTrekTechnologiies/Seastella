package com.seastella.notification.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One alert for one person. The in-app record and the source of any email
 * sent for it, so the bell and the inbox always say the same thing.
 */
@Entity
@Table(name = "notification")
class Notification extends BaseEntity {

    enum Category {
        /** The recipient has something to do. */
        ACTION,
        /** Something they should know happened. */
        UPDATE,
        /** A spare's maintenance colour status changed. */
        MAINTENANCE
    }

    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 16)
    private Category category;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", length = 2000)
    private String body;

    @Column(name = "entity_type", length = 48)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "vessel_id")
    private Long vesselId;

    @Column(name = "due_status", length = 24)
    private String dueStatus;

    @Column(name = "in_app", nullable = false)
    private boolean inApp;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
    }

    Notification(Long recipientUserId, String eventType, Category category, String title, String body,
                 String entityType, Long entityId, Long organizationId, Long vesselId,
                 String dueStatus, boolean inApp) {
        this.recipientUserId = recipientUserId;
        this.eventType = eventType;
        this.category = category;
        this.title = truncate(title, 200);
        this.body = truncate(body, 2000);
        this.entityType = entityType;
        this.entityId = entityId;
        this.organizationId = organizationId;
        this.vesselId = vesselId;
        this.dueStatus = dueStatus;
        this.inApp = inApp;
    }

    Long getRecipientUserId() { return recipientUserId; }
    String getEventType() { return eventType; }
    Category getCategory() { return category; }
    String getTitle() { return title; }
    String getBody() { return body; }
    String getEntityType() { return entityType; }
    Long getEntityId() { return entityId; }
    Long getOrganizationId() { return organizationId; }
    Long getVesselId() { return vesselId; }
    String getDueStatus() { return dueStatus; }
    boolean isInApp() { return inApp; }
    Instant getReadAt() { return readAt; }

    void markRead(Instant at) {
        if (readAt == null) readAt = at;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}
