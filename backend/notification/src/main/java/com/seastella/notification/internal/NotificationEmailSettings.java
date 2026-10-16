package com.seastella.notification.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "notification_email_settings")
class NotificationEmailSettings extends BaseEntity {

    static final long SINGLETON_ID = 1L;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected NotificationEmailSettings() {
    }

    boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
