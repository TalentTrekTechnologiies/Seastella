package com.seastella.notification.internal;

import com.seastella.core.api.time.BusinessTime;
import com.seastella.fleet.api.FleetDirectory;
import com.seastella.fleet.api.FleetDirectory.EquipmentExpiry;
import com.seastella.identity.api.Role;
import com.seastella.maintenance.api.MaintenanceStatusEngine;
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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reminders before a piece of equipment reaches its own expiry date.
 *
 * <p>Equipment carries {@code expiration_date}: the day the unit stops being
 * fit for use whatever its service history says - a life-limited battery, a
 * hydrostatic release, a liferaft bottle. It is not a service falling due and
 * it is not a certificate lapsing. Servicing does not move the date, and the
 * only remedies are replacement or re-certification, both of which take weeks
 * to arrange. That is why the notice has to come early.
 *
 * <p><b>Once per threshold, not once per night.</b> The client asked for one
 * notice at 60 days and one at 15 - the same ladder the colour bands use, so
 * the email and the equipment list say the same thing on the same day. A unit
 * expiring in three months would otherwise raise an alert every night for
 * ninety nights, and a mailbox that cries wolf nightly is one nobody reads.
 * {@link EquipmentExpiryAlertState} remembers the last threshold announced;
 * correcting the date frees the unit to announce again against the new one.
 *
 * <p>Runs nightly and at start-up, like the certificate sweep it is modelled
 * on. A failure is logged, never thrown: this runs on a timer with nobody
 * waiting on a response.
 */
@Component
class EquipmentExpiryMonitor {

    static final String EVENT_TYPE = "EQUIPMENT_EXPIRING";

    private static final Logger log = LoggerFactory.getLogger(EquipmentExpiryMonitor.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final FleetDirectory fleet;
    private final EquipmentExpiryAlertStateRepository states;
    private final MaintenanceStatusEngine engine;
    private final NotificationFanout fanout;
    private final TransactionTemplate tx;
    private final List<Integer> thresholds;

    EquipmentExpiryMonitor(FleetDirectory fleet, EquipmentExpiryAlertStateRepository states,
                           MaintenanceStatusEngine engine, NotificationFanout fanout,
                           PlatformTransactionManager transactions,
                           @Value("${seastella.notification.equipment.warning-days:60,15,0}")
                           List<Integer> warningDays) {
        this.fleet = fleet;
        this.states = states;
        this.engine = engine;
        this.fanout = fanout;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.thresholds = warningDays.stream().sorted(Comparator.reverseOrder()).toList();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        scan();
    }

    @Scheduled(cron = "${seastella.notification.equipment.scan-cron:0 25 0 * * *}",
            zone = "${seastella.time-zone:Asia/Kolkata}")
    public void scan() {
        try {
            tx.executeWithoutResult(status -> announceCrossings());
        } catch (RuntimeException e) {
            log.error("Equipment expiry scan failed", e);
        }
    }

    private void announceCrossings() {
        if (thresholds.isEmpty()) return;
        LocalDate today = BusinessTime.today();

        // Bounded by the widest warning: a unit expiring in five years is not
        // read at all, let alone considered.
        int widest = thresholds.get(0);
        List<EquipmentExpiry> expiring = fleet.equipmentExpiringBy(today.plusDays(widest));
        if (expiring.isEmpty()) return;

        Map<Long, EquipmentExpiryAlertState> known = states
                .findBySpareIdIn(expiring.stream().map(EquipmentExpiry::spareId).toList()).stream()
                .collect(Collectors.toMap(EquipmentExpiryAlertState::getSpareId, Function.identity(),
                        (first, second) -> first));

        int announced = 0;
        for (EquipmentExpiry unit : expiring) {
            long daysLeft = ChronoUnit.DAYS.between(today, unit.expiryDate());

            // The tightest threshold this unit has now reached. Crossing two at
            // once - a date entered late, or a unit added already expired - is
            // one notice at the tighter of them, not one notice for each.
            Integer crossed = thresholds.stream()
                    .filter(t -> daysLeft <= t)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            if (crossed == null) continue;

            EquipmentExpiryAlertState state = known.get(unit.spareId());
            boolean alreadyToldAtThisStage = state != null
                    && state.getLastThresholdDays() <= crossed
                    && state.getExpiryDate().equals(unit.expiryDate());
            if (alreadyToldAtThisStage) continue;

            alert(unit, daysLeft);
            Instant now = Instant.now();
            if (state == null) {
                states.save(new EquipmentExpiryAlertState(unit.spareId(), crossed, unit.expiryDate(), now));
            } else {
                state.announced(crossed, unit.expiryDate(), now);
                states.save(state);
            }
            announced++;
        }
        if (announced > 0) log.info("Equipment expiry: {} reminder(s) raised", announced);
    }

    private void alert(EquipmentExpiry unit, long daysLeft) {
        String where = unit.vesselName() == null ? "the fleet" : unit.vesselName();
        String what = unit.path() == null ? unit.name() : unit.name() + " (" + unit.path() + ")";
        String when = DATE.format(unit.expiryDate());

        AlertMessages.Message message = daysLeft < 0
                ? new AlertMessages.Message(Notification.Category.ACTION,
                        "Equipment expired: " + unit.name(),
                        what + " on " + where + " expired on " + when
                                + ". It should not stay in service until it is replaced or re-certified.")
                : new AlertMessages.Message(Notification.Category.ACTION,
                        "Equipment expiring: " + unit.name(),
                        what + " on " + where + " expires on " + when + " ("
                                + (daysLeft == 0 ? "today" : daysLeft + (daysLeft == 1 ? " day" : " days") + " from now")
                                + "). Arrange the replacement or the re-certification now.");

        // The band the equipment list is drawing for this same date, so the
        // alert and the screen cannot disagree about what colour the unit is.
        String band = engine.classify((int) daysLeft, unit.organizationId()).name();
        fanout.equipmentExpiryAlert(unit, message,
                List.of(Role.CAPTAIN, Role.SHIP_MANAGER, Role.TECHNICAL_HEAD), band);
    }
}
