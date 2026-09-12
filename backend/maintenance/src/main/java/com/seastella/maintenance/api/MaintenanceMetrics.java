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

    /**
     * Every tracked spare as a plottable point, for the maintenance radar.
     *
     * @param limit hard cap, so a very large fleet cannot produce an unbounded
     *              response
     */
    List<RadarPoint> radarPoints(Set<Long> vesselIds, int limit);

    /** Per-vessel due and overdue counts, for the fleet drill-down table. */
    Map<Long, VesselDueCounts> perVessel(Set<Long> vesselIds);

    record DueItem(
            Long spareId, Long vesselId, String vesselName, String spareName,
            String sparePath, String categoryCode,
            LocalDate nextDueDate, Integer daysRemaining,
            DueStatus status, String colour, String shape, String basis) {}

    record VesselDueCounts(long dueSoon, long overdue, long total) {}

    /**
     * Every tracked spare reduced to the three values a polar plot needs.
     *
     * <p>Deliberately compact: this is one row per spare across the fleet, so
     * the payload stays small enough to send whole rather than paginated, and
     * the client can plot it without a second request.
     */
    record RadarPoint(
            Long spareId, Long vesselId, String vesselName, String spareName,
            String categoryCode, Integer daysRemaining, DueStatus status) {}
}
