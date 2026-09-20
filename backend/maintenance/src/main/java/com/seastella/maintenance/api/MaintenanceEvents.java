package com.seastella.maintenance.api;

import com.seastella.core.api.event.DomainEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Domain events published by the maintenance module. */
public final class MaintenanceEvents {

    private MaintenanceEvents() {}

    /**
     * One or more spares on a vessel moved into a more severe colour band
     * (SoW s11). Grouped per vessel, so a night that tips six spares on one
     * ship into "urgent" is one alert naming six spares, not six alerts.
     */
    public record DueStatusChanged(
            Long organizationId,
            Long vesselId,
            String vesselName,
            List<Change> changes,
            Instant occurredAt) implements DomainEvent {

        public DueStatusChanged {
            changes = List.copyOf(changes);
        }

        @Override
        public String eventType() { return "MAINTENANCE_DUE_STATUS_CHANGED"; }

        @Override
        public String summary() {
            return vesselName + ": " + changes.size()
                    + (changes.size() == 1 ? " spare changed" : " spares changed") + " maintenance status";
        }

        /** The most severe status announced, for the alert's own marker. */
        public DueStatus worst() {
            return changes.stream().map(Change::to)
                    .max((a, b) -> Integer.compare(severity(a), severity(b)))
                    .orElse(DueStatus.NORMAL);
        }
    }

    public record Change(
            Long spareId,
            String spareName,
            String sparePath,
            DueStatus from,
            DueStatus to,
            Integer daysRemaining,
            LocalDate nextDueDate,
            DueAssessment.Basis basis) {}

    /** Orders the bands by urgency: not tracked and normal lowest, overdue highest. */
    public static int severity(DueStatus status) {
        if (status == null) return 0;
        return switch (status) {
            case NOT_TRACKED, NORMAL -> 0;
            case APPROACHING -> 1;
            case URGENT -> 2;
            case DUE -> 3;
            case OVERDUE -> 4;
        };
    }
}
