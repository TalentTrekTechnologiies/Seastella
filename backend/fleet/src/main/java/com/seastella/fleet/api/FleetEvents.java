package com.seastella.fleet.api;

import com.seastella.core.api.event.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Domain events published by the fleet module. */
public final class FleetEvents {

    private FleetEvents() {}

    /**
     * A spare's last annual service date was set or changed (SoW s9.3). The
     * maintenance module restarts the spare's calendar cycle from it.
     */
    public record ServiceDateChanged(
            Long spareId,
            Long vesselId,
            Long organizationId,
            LocalDate previousDate,
            LocalDate lastAnnualServiceDate,
            Long actorUserId,
            Instant occurredAt) implements DomainEvent {

        @Override
        public String eventType() { return "SERVICE_DATE_CHANGED"; }

        @Override
        public String summary() {
            return "Last annual service date set to " + lastAnnualServiceDate;
        }
    }

    /**
     * A running-hour reading was recorded against a spare. The maintenance
     * module re-projects the spare's running-hour due date from it (RHR-05).
     */
    public record RunningHoursRecorded(
            Long spareId,
            String spareName,
            Long vesselId,
            Long organizationId,
            BigDecimal previousHours,
            BigDecimal readingHours,
            LocalDate readingDate,
            Long actorUserId,
            Instant occurredAt) implements DomainEvent {

        @Override
        public String eventType() { return "RUNNING_HOURS_RECORDED"; }

        @Override
        public String summary() {
            return spareName + ": running hours recorded at " + readingHours.toPlainString() + " h";
        }
    }

    /**
     * A replacement part's stock changed (SoW s9.5). Published on every change;
     * {@code nowBelowMinimum} says whether this change is what took it below
     * the minimum to hold, which is the moment worth alerting (SPR-14).
     */
    public record PartStockChanged(
            Long partId,
            String partName,
            String partNumber,
            Long vesselId,
            String vesselName,
            Long organizationId,
            int previousQuantity,
            int quantityOnHand,
            int minimumQuantity,
            boolean nowBelowMinimum,
            Long actorUserId,
            Instant occurredAt) implements DomainEvent {

        @Override
        public String eventType() { return "PART_STOCK_CHANGED"; }

        @Override
        public String summary() {
            return partName + ": " + previousQuantity + " to " + quantityOnHand
                    + (nowBelowMinimum ? " (below the minimum of " + minimumQuantity + ")" : "");
        }
    }
}
