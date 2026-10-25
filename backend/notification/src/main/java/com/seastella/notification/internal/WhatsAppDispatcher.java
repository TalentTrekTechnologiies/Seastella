package com.seastella.notification.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sends pending WhatsApp alerts - the email dispatcher's twin, on its own
 * channel, so a slow or refused WhatsApp message never holds up an email.
 *
 * <p>Each alert goes out as an approved template carrying the alert's title
 * and details. Meta's refusals that no retry will change - a template not yet
 * approved, a number not on WhatsApp - fail at once with Meta's reason, where
 * the Platform Admin sees it; a network failure is tried three times.
 */
@Component
class WhatsAppDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppDispatcher.class);
    private static final int MAX_ATTEMPTS = 3;

    static final String PAUSED = "WhatsApp alerts are paused by an administrator.";
    static final String NOT_SET_UP = "WhatsApp is not set up yet: the Meta phone number id and access token are missing.";

    private final NotificationDeliveryRepository deliveries;
    private final NotificationRepository notifications;
    private final NotificationEmailSettingsRepository settings;
    private final WhatsAppTemplateRepository templates;
    private final WhatsAppGateway gateway;
    private final TransactionTemplate tx;
    private final Defaults defaults;

    /** The template every alert uses unless the Platform Admin names another for its event. */
    record Defaults(String template, String language, boolean linkButton) {}

    WhatsAppDispatcher(NotificationDeliveryRepository deliveries, NotificationRepository notifications,
                       NotificationEmailSettingsRepository settings, WhatsAppTemplateRepository templates,
                       WhatsAppGateway gateway, PlatformTransactionManager transactions,
                       @Value("${seastella.notification.whatsapp.template:thawe_alert}") String template,
                       @Value("${seastella.notification.whatsapp.language:en}") String language,
                       @Value("${seastella.notification.whatsapp.link-button:true}") boolean linkButton) {
        this.deliveries = deliveries;
        this.notifications = notifications;
        this.settings = settings;
        this.templates = templates;
        this.gateway = gateway;
        this.tx = new TransactionTemplate(transactions);
        this.defaults = new Defaults(template, language, linkButton);
    }

    Defaults defaults() { return defaults; }

    @Scheduled(initialDelayString = "${seastella.notification.whatsapp.initial-delay-ms:15000}",
            fixedDelayString = "${seastella.notification.whatsapp.dispatch-interval-ms:10000}")
    public void dispatch() {
        try {
            tx.executeWithoutResult(status -> sendPending());
        } catch (RuntimeException e) {
            log.error("WhatsApp dispatch run failed", e);
        }
    }

    void sendPending() {
        List<NotificationDelivery> pending = deliveries.findTop50ByChannelAndStatusOrderByIdAsc(
                NotificationDelivery.WHATSAPP, NotificationDelivery.PENDING);
        if (pending.isEmpty()) return;

        if (!settings.whatsappEnabled()) {
            deliveries.skipPending(NotificationDelivery.WHATSAPP, NotificationDelivery.PENDING,
                    NotificationDelivery.SKIPPED, PAUSED);
            return;
        }

        Map<Long, Notification> byId = notifications.findAllById(
                        pending.stream().map(NotificationDelivery::getNotificationId).toList())
                .stream().collect(Collectors.toMap(Notification::getId, Function.identity()));

        int sent = 0;
        for (NotificationDelivery delivery : pending) {
            Notification n = byId.get(delivery.getNotificationId());
            if (n == null) {
                delivery.skipped("The notification no longer exists.");
                continue;
            }
            if (!gateway.configured()) {
                delivery.skipped(NOT_SET_UP);
                continue;
            }
            try {
                gateway.send(outgoing(delivery.getAddress(), n));
                delivery.sent(Instant.now());
                sent++;
            } catch (WhatsAppGateway.WhatsAppException e) {
                if (e.permanent()) {
                    delivery.rejected(e.getMessage());
                } else {
                    delivery.failed(e.getMessage(), MAX_ATTEMPTS);
                }
                log.warn("WhatsApp to {} failed (attempt {}): {}", masked(delivery.getAddress()),
                        delivery.getAttempts(), e.getMessage());
            }
        }
        deliveries.saveAll(pending);
        if (sent > 0) log.info("Sent {} WhatsApp alert(s)", sent);
    }

    WhatsAppGateway.Outgoing outgoing(String to, Notification n) {
        WhatsAppTemplate chosen = templates.findByEventType(n.getEventType()).orElse(null);
        String name = chosen == null ? defaults.template() : chosen.getTemplateName();
        String language = chosen == null ? defaults.language() : chosen.getLanguageCode();
        boolean button = chosen == null ? defaults.linkButton() : chosen.isLinkButton();
        return new WhatsAppGateway.Outgoing(to, name, language,
                List.of(n.getTitle(), n.getBody() == null ? "" : n.getBody()),
                button ? linkSuffix(n) : null);
    }

    /**
     * The end of the "Open" button's link. The template holds the site
     * ("https://thaweone.com/{{1}}"); this adds where the alert points.
     */
    static String linkSuffix(Notification n) {
        return "SERVICE_REQUEST".equals(n.getEntityType()) && n.getEntityId() != null
                ? "requests/" + n.getEntityId()
                : "?from=whatsapp";
    }

    /** Logs carry the last four digits only. */
    static String masked(String number) {
        return number == null || number.length() < 4 ? "?" : "..." + number.substring(number.length() - 4);
    }
}
