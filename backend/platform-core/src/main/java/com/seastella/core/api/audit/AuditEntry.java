package com.seastella.core.api.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One immutable record of a consequential action (AUD-01).
 *
 * <p><b>This entity is deliberately append-only and has no setters.</b> It does
 * not extend {@code BaseEntity}, because an audit row has no "updated_at" and no
 * version - it is written once and never touched again. Tamper resistance is
 * enforced at two levels:
 *
 * <ul>
 *   <li><b>Application:</b> no setter, and no mutating repository method.</li>
 *   <li><b>Database:</b> the application's runtime role holds only INSERT and
 *       SELECT on this table. The migration performs the grant, and
 *       {@code AuditTamperTest} (S-46) asserts that an UPDATE fails.</li>
 * </ul>
 *
 * <p>The application-level guarantee alone would be worth little, since any
 * future service could issue native SQL. The grant is what makes it real.
 */
@Entity
@Table(name = "audit_entry")
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_role", length = 32)
    private String actorRole;

    @Column(name = "action", nullable = false, length = 80)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 80)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "vessel_id")
    private Long vesselId;

    @Column(name = "before_value", columnDefinition = "text")
    private String beforeValue;

    @Column(name = "after_value", columnDefinition = "text")
    private String afterValue;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditEntry() {
        // JPA only.
    }

    private AuditEntry(Builder b) {
        this.actorUserId = b.actorUserId;
        this.actorRole = b.actorRole;
        this.action = b.action;
        this.entityType = b.entityType;
        this.entityId = b.entityId;
        this.organizationId = b.organizationId;
        this.vesselId = b.vesselId;
        this.beforeValue = b.beforeValue;
        this.afterValue = b.afterValue;
        this.ipAddress = b.ipAddress;
        this.userAgent = b.userAgent;
        this.occurredAt = b.occurredAt == null ? Instant.now() : b.occurredAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getId() { return id; }
    public Long getActorUserId() { return actorUserId; }
    public String getActorRole() { return actorRole; }
    public String getAction() { return action; }
    public String getEntityType() { return entityType; }
    public Long getEntityId() { return entityId; }
    public Long getOrganizationId() { return organizationId; }
    public Long getVesselId() { return vesselId; }
    public String getBeforeValue() { return beforeValue; }
    public String getAfterValue() { return afterValue; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public Instant getOccurredAt() { return occurredAt; }

    public static final class Builder {
        private Long actorUserId;
        private String actorRole;
        private String action;
        private String entityType;
        private Long entityId;
        private Long organizationId;
        private Long vesselId;
        private String beforeValue;
        private String afterValue;
        private String ipAddress;
        private String userAgent;
        private Instant occurredAt;

        public Builder actor(Long userId, String role) {
            this.actorUserId = userId;
            this.actorRole = role;
            return this;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        public Builder entity(String type, Long id) {
            this.entityType = type;
            this.entityId = id;
            return this;
        }

        public Builder scope(Long organizationId, Long vesselId) {
            this.organizationId = organizationId;
            this.vesselId = vesselId;
            return this;
        }

        public Builder before(String json) {
            this.beforeValue = json;
            return this;
        }

        public Builder after(String json) {
            this.afterValue = json;
            return this;
        }

        public Builder request(String ip, String userAgent) {
            this.ipAddress = ip;
            this.userAgent = userAgent;
            return this;
        }

        public Builder occurredAt(Instant at) {
            this.occurredAt = at;
            return this;
        }

        public AuditEntry build() {
            if (action == null || action.isBlank()) {
                throw new IllegalStateException("audit action is required");
            }
            if (entityType == null || entityType.isBlank()) {
                throw new IllegalStateException("audit entityType is required");
            }
            return new AuditEntry(this);
        }
    }
}
