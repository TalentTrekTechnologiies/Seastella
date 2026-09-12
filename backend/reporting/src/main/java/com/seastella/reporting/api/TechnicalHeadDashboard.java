package com.seastella.reporting.api;

import com.seastella.maintenance.api.MaintenanceMetrics;
import com.seastella.reporting.api.DashboardCommon.Distribution;
import com.seastella.reporting.api.DashboardCommon.Kpi;
import com.seastella.reporting.api.DashboardCommon.Meta;
import com.seastella.servicerequest.api.ServiceRequestMetrics;

import java.math.BigDecimal;
import java.util.List;

/**
 * Fleet health for one organization (SoW s8.1).
 *
 * <p>Answers "what across my fleet needs attention?". Note
 * {@link #resolutionSplit}: requests closed without cost against those needing
 * an engineer visit is the figure that shows whether the troubleshooting
 * assistant is paying for itself, and s8.1 asks for it explicitly.
 *
 * <p>This role has no operational actions - s5 gives it monitoring and
 * drill-down only - so there are no action queues here, deliberately.
 */
public record TechnicalHeadDashboard(
        Meta meta,
        List<Kpi> kpis,
        Distribution vesselStatus,
        Distribution spareHealth,
        Distribution requestsByStage,
        Distribution spareCriticality,
        ServiceRequestMetrics.ResolutionSplit resolutionSplit,
        List<VesselHealthRow> vessels,
        List<MaintenanceMetrics.DueItem> overdue,
        List<MaintenanceMetrics.DueItem> dueSoon,
        InvoiceRollup invoices,
        List<ServiceRequestMetrics.ActivityItem> recentActivity) {

    /** One row of the fleet drill-down table: fleet to vessel to spare. */
    public record VesselHealthRow(
            Long vesselId, String vesselName, String imoNumber, String vesselType,
            String status, long spareCount, long dueSoon, long overdue,
            long openRequests, long partShortages) {}

    public record InvoiceRollup(
            long pendingCount, BigDecimal pendingValue,
            long acceptedCount, BigDecimal acceptedValue, String currency) {}
}
