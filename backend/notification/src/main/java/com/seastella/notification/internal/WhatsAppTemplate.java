package com.seastella.notification.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * The approved Meta template one kind of alert uses, when it is not the
 * platform's default. Every template receives the same two parameters - the
 * alert's title and its details - so any approved wording that has them can
 * be swapped in here without a code change.
 */
@Entity
@Table(name = "whatsapp_template")
class WhatsAppTemplate extends BaseEntity {

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Column(name = "template_name", nullable = false, length = 80)
    private String templateName;

    @Column(name = "language_code", nullable = false, length = 12)
    private String languageCode;

    @Column(name = "link_button", nullable = false)
    private boolean linkButton;

    protected WhatsAppTemplate() {
    }

    WhatsAppTemplate(String eventType) {
        this.eventType = eventType;
    }

    String getEventType() { return eventType; }
    String getTemplateName() { return templateName; }
    String getLanguageCode() { return languageCode; }
    boolean isLinkButton() { return linkButton; }

    void use(String templateName, String languageCode, boolean linkButton) {
        this.templateName = templateName;
        this.languageCode = languageCode;
        this.linkButton = linkButton;
    }
}
