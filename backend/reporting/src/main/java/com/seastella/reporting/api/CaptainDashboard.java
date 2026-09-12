package com.seastella.reporting.api;

import com.seastella.fleet.api.FleetMetrics;
import com.seastella.maintenance.api.MaintenanceMetrics;
import com.seastella.reporting.api.DashboardCommon.Kpi;
import com.seastella.reporting.api.DashboardCommon.Meta;
import com.seastella.servicerequest.api.ServiceRequestMetrics;

import java.util.List;

/**
 * One vessel, kept deliberately simple (SoW s8.3, s12 usability).
 *
 * <p><b>There is no invoice field anywhere in this record.</b> SoW s12 excludes
 * the Captain from cost data, and the exclusion is structural rather than a
 * filter: there is no amount field to populate, so a future change to the
 * dashboard service cannot accidentally leak one. The Captain sees
 * {@code invoiceAccepted} as a boolean on a request's status - that a cost was
 * agreed, never what it was.
 */
public record CaptainDashboard(
        Meta meta,
        VesselCard vessel,
        List<Kpi> kpis,
        List<MyRequest> myRequests,
        List<MaintenanceMetrics.DueItem> overdue,
        List<MaintenanceMetrics.DueItem> dueSoon,
        List<RunningHourEntry> runningHours,
        List<FleetMetrics.PartShortage> partShortages,
        List<ServiceRequestMetrics.ActivityItem> recentActivity) {

    public record VesselCard(
            Long vesselId, String name, String imoNumber, String callSign, String flag,
            String vesselType, String status, long spareCount, long criticalSpareCount) {}

    /**
     * A request as the Captain sees it. Carries the workflow position and
     * whether a reply is owed, but no money.
     */
    public record MyRequest(
            Long id, String requestNumber, Long spareId, String spareName, String sparePath,
            String title, String priority, String status, String statusLabel,
            boolean awaitingMyResponse, boolean invoiceAccepted, boolean engineerAssigned,
            String engineerName, java.time.Instant raisedAt, Integer ageDays) {}

    public record RunningHourEntry(
            Long spareId, String spareName, String sparePath, String categoryCode,
            String currentHours, java.time.LocalDate lastUpdated) {}
}
