package com.seastella.notification.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** The platform-wide switches for alert delivery outside the app: email, and WhatsApp. */
@Entity
@Table(name = "notification_email_settings")
class NotificationEmailSettings extends BaseEntity {

    static final long SINGLETON_ID = 1L;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "whatsapp_enabled", nullable = false)
    private boolean whatsappEnabled = true;

    protected NotificationEmailSettings() {
    }

    boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    boolean isWhatsappEnabled() {
        return whatsappEnabled;
    }

    void setWhatsappEnabled(boolean whatsappEnabled) {
        this.whatsappEnabled = whatsappEnabled;
    }
}
