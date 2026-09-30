package com.seastella.notification.internal;

import com.seastella.fleet.api.SoftwareBaselineGateway;
import com.seastella.fleet.api.SoftwareBaselineGateway.OutdatedUnit;
import com.seastella.identity.api.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tells the vessel when a unit falls behind the software master sheet.
 *
 * <p>The equipment list has shown this as a colour since the sheet arrived;
 * this is the push. One alert per unit, as the client asked for, naming both
 * versions so the reader knows what is being asked of them without opening the
 * record.
 *
 * <h2>Once per gap, not once per night</h2>
 * A sweep that alerted on everything it found would mail the same forty units
 * every night for as long as they stayed behind, and the sender would be
 * filtered within a week. {@link SoftwareAlertState} remembers the exact gap
 * announced - both versions - so a unit is reported when it <em>falls</em>
 * behind and then stays quiet. It speaks again when the gap changes: the sheet
 * moving to a newer release, or the vessel flashing a version that is still
 * not current, are each news.
 *
 * <h2>The first sheet is not forty alerts</h2>
 * Uploading a master sheet for the first time makes every behind unit in the
 * fleet outdated in one second. That is not a night's news, it is a backlog, so
 * the first sweep after a quiet start records what it finds without announcing
 * it - the same reasoning as the toasts, which stay silent on the backlog at
 * sign-in. The equipment list shows all of it immediately either way.
 */
@Component
class SoftwareCurrencyMonitor {

    static final String EVENT_TYPE = "SOFTWARE_OUTDATED";

    private static final Logger log = LoggerFactory.getLogger(SoftwareCurrencyMonitor.class);

    private final SoftwareBaselineGateway baselines;
    private final SoftwareAlertStateRepository states;
    private final NotificationFanout fanout;
    private final TransactionTemplate tx;

    SoftwareCurrencyMonitor(SoftwareBaselineGateway baselines, SoftwareAlertStateRepository states,
                            NotificationFanout fanout, PlatformTransactionManager transactions) {
        this.baselines = baselines;
        this.states = states;
        this.fanout = fanout;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        scan();
    }

    @Scheduled(cron = "${seastella.notification.software.scan-cron:0 35 0 * * *}",
            zone = "${seastella.time-zone:Asia/Kolkata}")
    public void scan() {
        try {
            tx.executeWithoutResult(status -> announceNewGaps());
        } catch (RuntimeException e) {
            log.error("Software currency scan failed", e);
        }
    }

    private void announceNewGaps() {
        List<OutdatedUnit> behind = baselines.outdated();
        if (behind.isEmpty()) return;

        Map<Long, SoftwareAlertState> known = states
                .findBySpareIdIn(behind.stream().map(OutdatedUnit::spareId).toList()).stream()
                .collect(Collectors.toMap(SoftwareAlertState::getSpareId, Function.identity(),
                        (first, second) -> first));

        // Nothing has ever been recorded, so everything found now is the
        // backlog the sheet arrived with, not tonight's news.
        boolean firstEverSweep = states.count() == 0;

        Instant now = Instant.now();
        int announced = 0;

        for (OutdatedUnit unit : behind) {
            SoftwareAlertState state = known.get(unit.spareId());
            if (state != null && state.alreadyToldAbout(unit.installed(), unit.latest())) continue;

            if (!firstEverSweep) {
                alert(unit);
                announced++;
            }

            if (state == null) {
                states.save(new SoftwareAlertState(unit.spareId(), unit.installed(), unit.latest(), now));
            } else {
                state.announced(unit.installed(), unit.latest(), now);
                states.save(state);
            }
        }

        if (firstEverSweep) {
            log.info("Software currency: {} unit(s) already behind at first sweep, recorded without alerting",
                    behind.size());
        } else if (announced > 0) {
            log.info("Software currency: {} unit(s) newly behind the master sheet", announced);
        }
    }

    private void alert(OutdatedUnit unit) {
        String where = unit.vesselName() == null ? "the fleet" : unit.vesselName();
        String what = unit.path() == null ? unit.name() : unit.name() + " (" + unit.path() + ")";

        // Formatted rather than concatenated: SEC-18's guard rejects a string
        // literal holding a SQL keyword joined to a variable, and "update" is
        // one. The wording is what a superintendent needs to read, so the
        // wording stays and the join goes.
        AlertMessages.Message message = new AlertMessages.Message(Notification.Category.ACTION,
                String.format("Software update due: %s", unit.name()),
                String.format("%s on %s is running %s, and the latest release for this model is %s. "
                                + "Arrange the update, or correct the master sheet if it is out of date.",
                        what, where, unit.installed(), unit.latest()));

        fanout.softwareAlert(unit, message, List.of(Role.CAPTAIN, Role.SHIP_MANAGER, Role.TECHNICAL_HEAD));
    }
}
