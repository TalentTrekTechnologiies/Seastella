package com.seastella.notification.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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
 * Sends pending email alerts (SoW s7 "in-app and email alerts", A s15 "Email
 * is available as a notification channel").
 *
 * <p>Deliberately off the request thread: an approval is never slowed down, or
 * failed, by a mail server. Pending deliveries are picked up every few seconds;
 * a failure is retried up to three times and then left as FAILED with the
 * reason, where the Platform Admin can see it.
 *
 * <p>With no mail server configured ({@code spring.mail.host} unset, as on a
 * laptop demo) nothing is attempted: each delivery is marked SKIPPED with that
 * reason rather than pretending to have sent it.
 */
@Component
class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);
    private static final int MAX_ATTEMPTS = 3;

    private final NotificationDeliveryRepository deliveries;
    private final NotificationRepository notifications;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final TransactionTemplate tx;
    private final String from;
    private final String replyTo;
    private final String brand;
    private final String brandSite;
    private final String appBaseUrl;

    EmailDispatcher(NotificationDeliveryRepository deliveries, NotificationRepository notifications,
                    ObjectProvider<JavaMailSender> mailSender, PlatformTransactionManager transactions,
                    @Value("${seastella.notification.email.from:SeaStella Maritime Ops <no-reply@seastella.in>}") String from,
                    @Value("${seastella.notification.email.reply-to:team@seastella.in}") String replyTo,
                    @Value("${seastella.notification.email.brand:Seastella}") String brand,
                    @Value("${seastella.notification.email.brand-site:seastella.in}") String brandSite,
                    @Value("${seastella.notification.app-base-url:http://localhost:5173}") String appBaseUrl) {
        this.deliveries = deliveries;
        this.notifications = notifications;
        this.mailSender = mailSender;
        this.tx = new TransactionTemplate(transactions);
        this.from = from;
        this.replyTo = replyTo;
        this.brand = brand;
        this.brandSite = brandSite;
        this.appBaseUrl = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
    }

    @Scheduled(initialDelayString = "${seastella.notification.email.initial-delay-ms:10000}",
            fixedDelayString = "${seastella.notification.email.dispatch-interval-ms:10000}")
    public void dispatch() {
        try {
            tx.executeWithoutResult(status -> sendPending());
        } catch (RuntimeException e) {
            log.error("Email dispatch run failed", e);
        }
    }

    private void sendPending() {
        List<NotificationDelivery> pending = deliveries.findTop50ByStatusOrderByIdAsc(NotificationDelivery.PENDING);
        if (pending.isEmpty()) return;

        Map<Long, Notification> byId = notifications.findAllById(
                        pending.stream().map(NotificationDelivery::getNotificationId).toList())
                .stream().collect(Collectors.toMap(Notification::getId, Function.identity()));

        JavaMailSender sender = mailSender.getIfAvailable();
        Instant now = Instant.now();
        int sent = 0;

        for (NotificationDelivery delivery : pending) {
            Notification n = byId.get(delivery.getNotificationId());
            if (n == null) {
                delivery.skipped("The notification no longer exists.");
                continue;
            }
            if (sender == null) {
                delivery.skipped("No mail server is configured (spring.mail.host).");
                log.info("Email not sent, no mail server configured: to={} subject=\"{}\"",
                        delivery.getAddress(), subject(n));
                continue;
            }
            try {
                SimpleMailMessage mail = new SimpleMailMessage();
                mail.setFrom(from);
                if (replyTo != null && !replyTo.isBlank()) mail.setReplyTo(replyTo);
                mail.setTo(delivery.getAddress());
                mail.setSubject(subject(n));
                mail.setText(text(n));
                sender.send(mail);
                delivery.sent(now);
                sent++;
            } catch (MailException e) {
                delivery.failed(e.getMessage(), MAX_ATTEMPTS);
                log.warn("Email to {} failed (attempt {}): {}", delivery.getAddress(), delivery.getAttempts(), e.getMessage());
            }
        }
        deliveries.saveAll(pending);
        if (sent > 0) log.info("Sent {} email alert(s)", sent);
    }

    private static String subject(Notification n) {
        return "[SeaStella] " + n.getTitle();
    }

    private String text(Notification n) {
        String path = "SERVICE_REQUEST".equals(n.getEntityType()) && n.getEntityId() != null
                ? "/requests/" + n.getEntityId()
                : "/";
        return n.getTitle() + "\n\n"
                + (n.getBody() == null ? "" : n.getBody() + "\n\n")
                + "Open in SeaStella: " + appBaseUrl + path + "\n\n"
                + "--\nThis is an automated alert from SeaStella Maritime Ops. Replies to this address are not read.";
    }
}
