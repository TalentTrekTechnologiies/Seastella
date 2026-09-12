package com.seastella.maintenance.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maintenance aggregates for the dashboards.
 *
 * <p>Every figure here is derived from {@link MaintenanceStatusEngine}'s bands,
 * so a dashboard count and a spare screen's badge can never disagree - which is
 * exactly what the master brief's "one maintenance-status engine" requirement
 * is protecting against.
 */
public interface MaintenanceMetrics {

    /** Counts per colour band for the given vessels. */
    Map<DueStatus, Long> dueBreakdown(Set<Long> vesselIds);

    /** Spares due within the window, soonest first. */
    List<DueItem> dueSoon(Set<Long> vesselIds, int withinDays, int limit);

    /** Spares already past their due date, most overdue first. */
    List<DueItem> overdue(Set<Long> vesselIds, int limit);

    long overdueCount(Set<Long> vesselIds);

    long dueSoonCount(Set<Long> vesselIds, int withinDays);

    /** Spares needing attention: approaching, urgent, due or overdue. */
    long attentionCount(Set<Long> vesselIds);

    /** Per-vessel due and overdue counts, for the fleet drill-down table. */
    Map<Long, VesselDueCounts> perVessel(Set<Long> vesselIds);

    record DueItem(
            Long spareId, Long vesselId, String vesselName, String spareName,
            String sparePath, String categoryCode,
            LocalDate nextDueDate, Integer daysRemaining,
            DueStatus status, String colour, String shape, String basis) {}

    record VesselDueCounts(long dueSoon, long overdue, long total) {}
}
