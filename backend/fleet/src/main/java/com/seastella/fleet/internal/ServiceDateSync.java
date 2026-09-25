package com.seastella.fleet.internal;

import com.seastella.core.api.event.DomainEventPublisher;
import com.seastella.fleet.api.FleetEvents;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Keeps a spare's own last-service date in step with its history, and asks the
 * maintenance engine to re-band it. Shared by every way a history entry is
 * written by hand - one at a time on the spare, or many from a spreadsheet.
 */
@Component
class ServiceDateSync {

    private final SpareServiceRecordRepository records;
    private final SpareRepository spares;
    private final VesselRepository vessels;
    private final DomainEventPublisher events;

    ServiceDateSync(SpareServiceRecordRepository records, SpareRepository spares, VesselRepository vessels,
                    DomainEventPublisher events) {
        this.records = records;
        this.spares = spares;
        this.vessels = vessels;
        this.events = events;
    }

    /**
     * Entering a service that is older than the newest one must not move the
     * due date backwards; removing the newest moves it back to the one before.
     */
    void applyNewestDate(Spare spare, Long actorUserId) {
        LocalDate previous = spare.getLastAnnualServiceDate();
        LocalDate newest = records.newestServiceDate(spare.getId());
        if (Objects.equals(newest, previous)) return;

        spare.setLastAnnualServiceDate(newest);
        spares.save(spare);
        // The same event the equipment screen publishes, so one mechanism
        // restarts the maintenance cycle however the date came to change.
        Long organizationId = vessels.findById(spare.getVesselId())
                .map(Vessel::getOrganizationId).orElse(null);
        events.publish(new FleetEvents.ServiceDateChanged(spare.getId(), spare.getVesselId(), organizationId,
                previous, newest, actorUserId, Instant.now()));
    }
}
