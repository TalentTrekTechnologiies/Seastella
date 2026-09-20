package com.seastella.maintenance.internal;

import com.seastella.fleet.api.FleetEvents;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Starts or restarts calendar tracking from a spare's last annual service date
 * (SoW s7 "calendar-based service due-dates", s9.3).
 *
 * <p>The field is the <em>annual</em> service, so a spare without a calendar
 * rule gets one with a 365-day interval; one that has a rule keeps its interval
 * and restarts from the new date. This is how a newly added vessel, which
 * starts with no dates, comes under maintenance tracking.
 *
 * <p>Runs in the transaction that saved the date, so the date and the due date
 * commit together; the colour-status re-check runs after commit.
 */
@Component
class ServiceDateTracking {

    static final int ANNUAL_DAYS = 365;

    private final SpareMaintenanceRuleRepository rules;
    private final ApplicationEventPublisher internalEvents;

    ServiceDateTracking(SpareMaintenanceRuleRepository rules, ApplicationEventPublisher internalEvents) {
        this.rules = rules;
        this.internalEvents = internalEvents;
    }

    @EventListener
    public void onServiceDateChanged(FleetEvents.ServiceDateChanged change) {
        if (change.lastAnnualServiceDate() == null) {
            // Clearing the date keeps the last known cycle rather than
            // silently dropping a spare out of tracking.
            return;
        }
        List<SpareMaintenanceRule> calendar = rules.findBySpareIdAndActiveTrue(change.spareId()).stream()
                .filter(SpareMaintenanceRule::isCalendar)
                .toList();

        if (calendar.isEmpty()) {
            rules.save(SpareMaintenanceRule.calendar(change.spareId(), change.vesselId(),
                    ANNUAL_DAYS, change.lastAnnualServiceDate()));
        } else {
            for (SpareMaintenanceRule rule : calendar) {
                rule.completeService(change.lastAnnualServiceDate(), null);
                rules.save(rule);
            }
        }
        internalEvents.publishEvent(new RulesChanged(Set.of(change.spareId())));
    }
}
