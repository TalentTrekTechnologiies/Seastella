package com.seastella.reporting.api;

import com.seastella.invoice.api.InvoiceMetrics;
import com.seastella.reporting.api.DashboardCommon.ActionQueue;
import com.seastella.reporting.api.DashboardCommon.Kpi;
import com.seastella.reporting.api.DashboardCommon.Meta;
import com.seastella.servicerequest.api.ServiceRequestMetrics;

import java.util.List;

/**
 * The service operations pipeline (SoW s8.4).
 *
 * <p>Shaped as a board rather than a metrics page, because this role manages
 * flow: each field below is a stage of the SoW s6 workflow, in order, and each
 * is a queue with an action rather than a number.
 *
 * <p>{@link #completionReportsPendingRelay} is the s6.3 chain made explicit as
 * work: the Coordinator is the Ship Manager's only channel for completion, so
 * an unrelayed report is a Ship Manager who has not been told.
 */
public record ServiceCoordinatorDashboard(
        Meta meta,
        List<Kpi> kpis,
        ActionQueue<ServiceRequestMetrics.RequestSummary> incoming,
        ActionQueue<ServiceRequestMetrics.RequestSummary> liveAgentActive,
        ActionQueue<ServiceRequestMetrics.RequestSummary> awaitingTriage,
        ActionQueue<InvoiceMetrics.InvoiceSummary> invoicesPendingAcceptance,
        ActionQueue<InvoiceMetrics.InvoiceSummary> acceptedReadyToAssign,
        ActionQueue<ServiceRequestMetrics.RequestSummary> assignedInProgress,
        ActionQueue<ServiceRequestMetrics.RequestSummary> completionReportsPendingRelay,
        ServiceRequestMetrics.ResolutionSplit resolutionSplit,
        Turnaround turnaround,
        List<ServiceRequestMetrics.ActivityItem> recentActivity) {

    public record Turnaround(Double averageHoursLast30Days, Double averageHoursLast90Days,
                             long closedLast30Days) {}
}
