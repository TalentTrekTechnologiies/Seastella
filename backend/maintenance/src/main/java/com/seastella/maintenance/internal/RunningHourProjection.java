package com.seastella.maintenance.internal;

import com.seastella.fleet.api.FleetDirectory;
import com.seastella.fleet.api.FleetEvents;
import com.seastella.maintenance.api.MaintenanceCycle;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * Turns running hours into due dates (RHR-05, MNT-03) and restarts cycles after
 * a completed service (s6.3, s18).
 *
 * <h2>How fast is the meter running?</h2>
 * A running-hour limit becomes a date only with a consumption rate. When the
 * spare has two readings on different days within the last 180 days, the
 * observed average between them is used. Without that history, the projection
 * uses 24 hours a day - the most a meter can physically run - which gives the
 * <em>earliest</em> date the limit could be reached. Early is the safe side to
 * err on for bridge equipment; the date moves later on its own as readings
 * accumulate.
 */
@Service
class RunningHourProjection implements MaintenanceCycle {

    static final BigDecimal MAX_HOURS_PER_DAY = new BigDecimal("24");
    private static final int HISTORY_WINDOW_DAYS = 180;

    private final SpareMaintenanceRuleRepository rules;
    private final FleetDirectory fleet;
    private final ApplicationEventPublisher internalEvents;

    RunningHourProjection(SpareMaintenanceRuleRepository rules, FleetDirectory fleet,
                          ApplicationEventPublisher internalEvents) {
        this.rules = rules;
        this.fleet = fleet;
        this.internalEvents = internalEvents;
    }

    /** Same transaction as the reading: the due date commits with it or not at all. */
    @EventListener
    public void onReading(FleetEvents.RunningHoursRecorded reading) {
        BigDecimal rate = hoursPerDay(reading.spareId());
        boolean moved = false;
        for (SpareMaintenanceRule rule : rules.findBySpareIdAndActiveTrue(reading.spareId())) {
            if (rule.isCalendar()) continue;
            rule.projectDueDate(reading.readingHours(), reading.readingDate(), rate);
            rules.save(rule);
            moved = true;
        }
        if (moved) {
            internalEvents.publishEvent(new RulesChanged(Set.of(reading.spareId())));
        }
    }

    @Override
    @Transactional
    public void serviceCompleted(Long spareId, LocalDate serviceDate) {
        if (spareId == null || serviceDate == null) return;
        BigDecimal hours = fleet.spareRef(spareId).map(FleetDirectory.SpareRef::runningHours).orElse(null);

        List<SpareMaintenanceRule> active = rules.findBySpareIdAndActiveTrue(spareId);
        for (SpareMaintenanceRule rule : active) {
            rule.completeService(serviceDate, hours);
            if (!rule.isCalendar() && hours != null) {
                rule.projectDueDate(hours, serviceDate, hoursPerDay(spareId));
            }
            rules.save(rule);
        }
        if (!active.isEmpty()) {
            internalEvents.publishEvent(new RulesChanged(Set.of(spareId)));
        }
    }

    /** Observed average between readings, or the physical maximum without history. */
    BigDecimal hoursPerDay(Long spareId) {
        List<FleetDirectory.HourReading> recent = fleet.hourReadings(spareId, 24);
        if (recent.size() < 2) return MAX_HOURS_PER_DAY;

        FleetDirectory.HourReading latest = recent.get(0);
        FleetDirectory.HourReading earliest = null;
        for (FleetDirectory.HourReading r : recent) {
            long age = ChronoUnit.DAYS.between(r.readingDate(), latest.readingDate());
            if (age > 0 && age <= HISTORY_WINDOW_DAYS) earliest = r;
        }
        if (earliest == null) return MAX_HOURS_PER_DAY;

        long days = ChronoUnit.DAYS.between(earliest.readingDate(), latest.readingDate());
        BigDecimal rate = latest.hours().subtract(earliest.hours())
                .divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
        if (rate.signum() <= 0) return MAX_HOURS_PER_DAY;
        return rate.min(MAX_HOURS_PER_DAY);
    }
}
