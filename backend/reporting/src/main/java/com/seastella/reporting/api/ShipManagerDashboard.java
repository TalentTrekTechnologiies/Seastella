package com.seastella.reporting.api;

import com.seastella.invoice.api.InvoiceMetrics;
import com.seastella.maintenance.api.MaintenanceMetrics;
import com.seastella.reporting.api.DashboardCommon.ActionQueue;
import com.seastella.reporting.api.DashboardCommon.Distribution;
import com.seastella.reporting.api.DashboardCommon.Kpi;
import com.seastella.reporting.api.DashboardCommon.Meta;
import com.seastella.servicerequest.api.ServiceRequestMetrics;

import java.util.List;

/**
 * Assigned vessels only (SoW s8.2).
 *
 * <p>Action-first: this role holds two queues that block other people, so both
 * lead. {@link #invoicesAwaitingAcceptance} carries the gate explanation in its
 * {@code actionLabel}, because a Ship Manager who does not act on it is the
 * reason an engineer has not been dispatched.
 */
public record ShipManagerDashboard(
        Meta meta,
        List<Kpi> kpis,
        ActionQueue<ServiceRequestMetrics.RequestSummary> requestsAwaitingApproval,
        ActionQueue<InvoiceMetrics.InvoiceSummary> invoicesAwaitingAcceptance,
        List<VesselRow> vessels,
        Distribution requestsByStage,
        List<ServiceRequestMetrics.RequestSummary> inFlight,
        List<MaintenanceMetrics.DueItem> overdue,
        List<MaintenanceMetrics.DueItem> dueSoon,
        List<ServiceRequestMetrics.ActivityItem> recentActivity) {

    public record VesselRow(
            Long vesselId, String vesselName, String imoNumber, String status,
            long spareCount, long dueSoon, long overdue, long openRequests,
            long partShortages) {}
}
