package com.seastella.notification.internal;

import com.seastella.identity.api.AccountEmails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Invitation and password-reset emails.
 *
 * <p>Unlike alerts these are sent at once, on the caller's thread, so the
 * administrator who created an account learns straight away whether the
 * invitation reached the mail server. Each is logged like any other delivery -
 * the Platform Admin's delivery log shows it - but without the link: the link
 * is a credential and exists only in the email itself. None of these appear in
 * anyone's in-app inbox.
 */
@Component
class AccountEmailSender implements AccountEmails {

    private static final Logger log = LoggerFactory.getLogger(AccountEmailSender.class);
    private static final DateTimeFormatter EXPIRY =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    static final String INVITATION = "ACCOUNT_INVITATION";
    static final String PASSWORD_RESET = "ACCOUNT_PASSWORD_RESET";

    private final ObjectProvider<JavaMailSender> mailSender;
    private final NotificationRepository notifications;
    private final NotificationDeliveryRepository deliveries;
    private final TransactionTemplate tx;
    private final String from;
    private final String replyTo;
    private final String brand;
    private final String brandSite;

    AccountEmailSender(ObjectProvider<JavaMailSender> mailSender, NotificationRepository notifications,
                       NotificationDeliveryRepository deliveries, PlatformTransactionManager transactions,
                       @Value("${seastella.notification.email.from:SeaStella Maritime Ops <no-reply@seastella.in>}") String from,
                       @Value("${seastella.notification.email.reply-to:team@seastella.in}") String replyTo,
                       @Value("${seastella.notification.email.brand:Seastella}") String brand,
                       @Value("${seastella.notification.email.brand-site:seastella.in}") String brandSite) {
        this.mailSender = mailSender;
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.tx = new TransactionTemplate(transactions);
        this.from = from;
        this.replyTo = replyTo;
        this.brand = brand;
        this.brandSite = brandSite;
    }

    @Override
    public Delivery sendInvitation(Recipient to, String invitedBy, String roleLabel, String link, Instant expiresAt) {
        String subject = "Your SeaStella Maritime Ops account";
        String text = "Hello " + to.fullName() + ",\n\n"
                + invitedBy + " has created a SeaStella Maritime Ops account for you as " + roleLabel + ".\n\n"
                + "Choose your password to activate it:\n" + link + "\n\n"
                + "The link works once and expires " + EXPIRY.format(expiresAt) + ". "
                + "If it has expired, ask " + invitedBy + " to send a new invitation.\n\n"
                + "If you were not expecting this email you can ignore it. The account cannot be used until "
                + "a password is chosen from this link.\n\n"
                + footer();
        return send(to, INVITATION, "Invitation to SeaStella sent to " + to.email(),
                "Sent by " + invitedBy + " for the " + roleLabel + " account.", subject, text);
    }

    @Override
    public Delivery sendPasswordReset(Recipient to, String link, Instant expiresAt) {
        String subject = "Reset your SeaStella password";
        String text = "Hello " + to.fullName() + ",\n\n"
                + "A password reset was requested for your SeaStella Maritime Ops account (" + to.email() + ").\n\n"
                + "Choose a new password here:\n" + link + "\n\n"
                + "The link works once and expires " + EXPIRY.format(expiresAt) + ". "
                + "Using it signs you out of SeaStella on every other device.\n\n"
                + "If you did not ask for this, ignore this email: your current password keeps working.\n\n"
                + footer();
        return send(to, PASSWORD_RESET, "Password reset link sent to " + to.email(), null, subject, text);
    }

    private Delivery send(Recipient to, String eventType, String logTitle, String logBody,
                          String subject, String text) {
        JavaMailSender sender = mailSender.getIfAvailable();
        Delivery outcome;
        String error = null;
        if (sender == null) {
            outcome = Delivery.NOT_CONFIGURED;
            error = "No mail server is configured (spring.mail.host).";
            log.info("{} email not sent, no mail server configured: user={}", eventType, to.userId());
        } else {
            try {
                SimpleMailMessage mail = new SimpleMailMessage();
                mail.setFrom(from);
                if (replyTo != null && !replyTo.isBlank()) mail.setReplyTo(replyTo);
                mail.setTo(to.email());
                mail.setSubject(subject);
                mail.setText(text);
                sender.send(mail);
                outcome = Delivery.SENT;
            } catch (MailException e) {
                outcome = Delivery.FAILED;
                error = e.getMessage();
                log.warn("{} email to user {} failed: {}", eventType, to.userId(), e.getMessage());
            }
        }
        record(to, eventType, logTitle, logBody, outcome, error);
        return outcome;
    }

    /** The delivery log entry, written with its final status so the alert dispatcher never picks it up. */
    private void record(Recipient to, String eventType, String title, String body, Delivery outcome, String error) {
        try {
            tx.executeWithoutResult(status -> {
                Notification n = notifications.save(new Notification(to.userId(), eventType,
                        Notification.Category.ACTION, title, body, "AppUser", to.userId(),
                        null, null, null, false));
                NotificationDelivery d = new NotificationDelivery(n.getId(), NotificationDelivery.EMAIL, to.email());
                switch (outcome) {
                    case SENT -> d.sent(Instant.now());
                    case NOT_CONFIGURED -> d.skipped(error);
                    case FAILED -> d.failed(error, 1);
                }
                deliveries.save(d);
            });
        } catch (RuntimeException e) {
            // The email itself has already gone (or not); a logging failure must not undo that answer.
            log.error("Could not record the {} delivery for user {}", eventType, to.userId(), e);
        }
    }

    private String footer() {
        return "--\nSeaStella Maritime Ops. This mailbox is not monitored.";
    }
}
