package com.seastella.notification.internal;

import com.seastella.fleet.api.DocumentDirectory;
import com.seastella.fleet.api.DocumentDirectory.CertificateRef;
import com.seastella.identity.api.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Reminders before a certificate expires (SoW s7 "reminders before expiry",
 * s11; NOT-11, MNT-13).
 *
 * <p>Runs nightly and on start-up. Each certificate is announced once per
 * threshold it crosses - by default 90 days out, then 30, then 7, then on the
 * day it lapses - so a certificate expiring in three months does not alert
 * every night until then. The thresholds are configuration, not code, because
 * how much notice a fleet wants is theirs to decide (DOC-03).
 */
@Component
class CertificateExpiryMonitor {

    static final String EVENT_TYPE = "CERTIFICATE_EXPIRING";

    private static final Logger log = LoggerFactory.getLogger(CertificateExpiryMonitor.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final DocumentDirectory documents;
    private final CertificateAlertStateRepository states;
    private final NotificationFanout fanout;
    private final TransactionTemplate tx;
    private final List<Integer> thresholds;

    CertificateExpiryMonitor(DocumentDirectory documents, CertificateAlertStateRepository states,
                             NotificationFanout fanout, PlatformTransactionManager transactions,
                             @Value("${seastella.notification.certificate.warning-days:90,30,7,0}") List<Integer> warningDays) {
        this.documents = documents;
        this.states = states;
        this.fanout = fanout;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.thresholds = warningDays.stream().sorted(Comparator.reverseOrder()).toList();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        scan();
    }

    @Scheduled(cron = "${seastella.notification.certificate.scan-cron:0 20 0 * * *}")
    public void scan() {
        try {
            tx.executeWithoutResult(status -> announceCrossings());
        } catch (RuntimeException e) {
            log.error("Certificate expiry scan failed", e);
        }
    }

    private void announceCrossings() {
        if (thresholds.isEmpty()) return;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int widest = thresholds.get(0);
        List<CertificateRef> certificates = documents.certificatesExpiringBy(today.plusDays(widest));
        int announced = 0;

        for (CertificateRef certificate : certificates) {
            long daysLeft = ChronoUnit.DAYS.between(today, certificate.expiryDate());
            Integer crossed = thresholds.stream()
                    .filter(t -> daysLeft <= t)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            if (crossed == null) continue;

            CertificateAlertState state = states.findByDocumentId(certificate.id()).orElse(null);
            boolean alreadyToldAtThisStage = state != null
                    && state.getLastThresholdDays() <= crossed
                    && state.getExpiryDate().equals(certificate.expiryDate());
            if (alreadyToldAtThisStage) continue;

            alert(certificate, daysLeft);
            Instant now = Instant.now();
            if (state == null) {
                states.save(new CertificateAlertState(certificate.id(), crossed, certificate.expiryDate(), now));
            } else {
                state.announced(crossed, certificate.expiryDate(), now);
                states.save(state);
            }
            announced++;
        }
        if (announced > 0) log.info("Certificate expiry: {} reminder(s) raised", announced);
    }

    private void alert(CertificateRef certificate, long daysLeft) {
        String what = certificate.title()
                + (certificate.certificateNumber() == null ? "" : " (" + certificate.certificateNumber() + ")");
        String where = certificate.attachedTo() == null
                ? certificate.vesselName()
                : certificate.attachedTo() + " on " + certificate.vesselName();
        String when = DATE.format(certificate.expiryDate());

        AlertMessages.Message message = daysLeft < 0
                ? new AlertMessages.Message(Notification.Category.ACTION,
                        "Certificate expired: " + certificate.title(),
                        what + " for " + where + " expired on " + when
                                + ". The equipment is out of certification until a renewed certificate is uploaded.")
                : new AlertMessages.Message(Notification.Category.ACTION,
                        "Certificate expiring: " + certificate.title(),
                        what + " for " + where + " expires on " + when + " ("
                                + (daysLeft == 0 ? "today" : daysLeft + (daysLeft == 1 ? " day" : " days") + " from now")
                                + "). Arrange the renewal and upload the new certificate.");

        fanout.certificateAlert(certificate, message, List.of(Role.CAPTAIN, Role.SHIP_MANAGER, Role.TECHNICAL_HEAD));
    }
}
