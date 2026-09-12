package com.seastella.reporting.api;

import com.seastella.fleet.api.FleetMetrics;
import com.seastella.reporting.api.DashboardCommon.Distribution;
import com.seastella.reporting.api.DashboardCommon.Kpi;
import com.seastella.reporting.api.DashboardCommon.Meta;
import com.seastella.servicerequest.api.ServiceRequestMetrics;

import java.math.BigDecimal;
import java.util.List;

/**
 * Platform-wide operations (SoW s8.5).
 *
 * <p>The activity feed leads, not the metrics: s8.5 makes the consolidated feed
 * this role's primary instrument, on the reasoning that a Platform Admin needs
 * to know every update without being paged on each one.
 */
public record PlatformAdminDashboard(
        Meta meta,
        List<Kpi> kpis,
        List<FleetMetrics.OrganizationSummary> organizations,
        Distribution vesselStatus,
        Distribution requestStatus,
        List<UserRoleCount> usersByRole,
        List<ServiceRequestMetrics.ActivityItem> activityFeed,
        InvoiceTotals invoiceTotals,
        SystemStatus systemStatus) {

    public record UserRoleCount(String role, long count) {}

    public record InvoiceTotals(long raised, BigDecimal raisedValue,
                                long accepted, BigDecimal acceptedValue,
                                String currency) {}

    public record SystemStatus(long organizations, long vessels, long spares,
                               long users, long openRequests, long auditEntries,
                               boolean seedDataPresent) {}
}
