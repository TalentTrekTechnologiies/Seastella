package com.seastella.reporting.internal;

import com.seastella.fleet.api.FleetMetrics;
import com.seastella.fleet.api.VesselStatus;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.invoice.api.InvoiceMetrics;
import com.seastella.invoice.api.InvoiceStatus;
import com.seastella.reporting.api.DashboardCommon.Distribution;
import com.seastella.reporting.api.DashboardCommon.Kpi;
import com.seastella.reporting.api.DashboardCommon.Slice;
import com.seastella.reporting.api.PlatformAdminDashboard;
import com.seastella.servicerequest.api.ServiceRequestMetrics;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Platform-wide visibility (SoW s8.5).
 *
 * <p>The only dashboard that deliberately ignores vessel scope - a Platform
 * Admin's scope kind is PLATFORM, so the platform-wide metric variants are the
 * correct ones. Every other role's service passes an explicit vessel set.
 */
@Service
class PlatformAdminDashboardService {

    private static final int FEED_SIZE = 50;

    private final DashboardSupport support;
    private final FleetMetrics fleet;
    private final ServiceRequestMetrics requests;
    private final InvoiceMetrics invoices;
    private final JdbcTemplate jdbc;

    PlatformAdminDashboardService(DashboardSupport support, FleetMetrics fleet,
                                  ServiceRequestMetrics requests, InvoiceMetrics invoices,
                                  JdbcTemplate jdbc) {
        this.support = support;
        this.fleet = fleet;
        this.requests = requests;
        this.invoices = invoices;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PlatformAdminDashboard build() {
        AccessScope scope = support.requireRole(Role.PLATFORM_ADMIN);

        List<FleetMetrics.OrganizationSummary> organizations = fleet.organizationSummaries();
        Map<VesselStatus, Long> vesselStatus = fleet.vesselStatusBreakdownPlatformWide();
        Map<ServiceRequestStatus, Long> requestCounts = requests.countsByStatusPlatformWide();
        Map<InvoiceStatus, InvoiceMetrics.Aggregate> invoiceSummary = invoices.summaryPlatformWide();

        long vessels = vesselStatus.values().stream().mapToLong(Long::longValue).sum();
        long spares = scalar("select count(*) from spare");
        long users = scalar("select count(*) from app_user");
        long auditEntries = scalar("select count(*) from audit_entry");
        long openRequests = scalar("select count(*) from service_request where closed_at is null");
        boolean seeded = scalar("select count(*) from organization where seed_marker = 'SEED'") > 0;

        List<Kpi> kpis = List.of(
                Kpi.of("organizations", "Organizations", organizations.size()),
                Kpi.of("vessels", "Vessels", vessels),
                Kpi.of("users", "Users", users),
                Kpi.of("spares", "Spares", spares),
                Kpi.of("openRequests", "Open service requests", openRequests),
                Kpi.of("auditEntries", "Audit entries", auditEntries));

        InvoiceMetrics.Aggregate raised = invoiceSummary.getOrDefault(
                InvoiceStatus.RAISED, new InvoiceMetrics.Aggregate(0, BigDecimal.ZERO));
        InvoiceMetrics.Aggregate accepted = invoiceSummary.getOrDefault(
                InvoiceStatus.ACCEPTED, new InvoiceMetrics.Aggregate(0, BigDecimal.ZERO));

        return new PlatformAdminDashboard(
                support.meta(scope, "All organizations"),
                kpis,
                organizations,
                vesselStatusDistribution(vesselStatus),
                support.requestStageDistribution(requestCounts),
                usersByRole(),
                requests.recentActivityPlatformWide(FEED_SIZE),
                new PlatformAdminDashboard.InvoiceTotals(
                        raised.count(), raised.total(),
                        accepted.count(), accepted.total(), "USD"),
                new PlatformAdminDashboard.SystemStatus(
                        organizations.size(), vessels, spares, users,
                        openRequests, auditEntries, seeded));
    }

    private List<PlatformAdminDashboard.UserRoleCount> usersByRole() {
        List<PlatformAdminDashboard.UserRoleCount> rows = new ArrayList<>();
        jdbc.query("select role, count(*) as c from app_user group by role order by role",
                rs -> { rows.add(new PlatformAdminDashboard.UserRoleCount(
                        rs.getString("role"), rs.getLong("c"))); });
        return rows;
    }

    static Distribution vesselStatusDistribution(Map<VesselStatus, Long> counts) {
        List<Slice> slices = new ArrayList<>();
        for (VesselStatus s : VesselStatus.values()) {
            slices.add(new Slice(s.name(), s.label(), counts.getOrDefault(s, 0L),
                    s == VesselStatus.ACTIVE ? "green" : "neutral"));
        }
        return Distribution.of("vesselStatus", "Vessels by status", slices);
    }

    private long scalar(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
