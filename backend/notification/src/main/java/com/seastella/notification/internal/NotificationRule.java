package com.seastella.notification.internal;

import com.seastella.core.api.model.BaseEntity;
import com.seastella.identity.api.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Who hears about an event, and how (SoW s11, OI-03). The recipients within a
 * role are always resolved from scope - the Ship Manager of <em>that</em>
 * vessel, the Coordinators serving <em>that</em> organization - so a rule can
 * widen or narrow the audience by role but never reach outside anyone's scope.
 */
@Entity
@Table(name = "notification_rule")
class NotificationRule extends BaseEntity {

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_role", nullable = false, length = 32)
    private Role recipientRole;

    @Column(name = "in_app", nullable = false)
    private boolean inApp;

    @Column(name = "email", nullable = false)
    private boolean email;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "source_ref", length = 80)
    private String sourceRef;

    protected NotificationRule() {
    }

    String getEventType() { return eventType; }
    Role getRecipientRole() { return recipientRole; }
    boolean isInApp() { return inApp; }
    boolean isEmail() { return email; }
    boolean isActive() { return active; }
    String getSourceRef() { return sourceRef; }

    void configure(boolean inApp, boolean email, boolean active) {
        this.inApp = inApp;
        this.email = email;
        this.active = active;
    }
}
