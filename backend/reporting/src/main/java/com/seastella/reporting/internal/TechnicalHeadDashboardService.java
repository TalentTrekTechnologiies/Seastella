package com.seastella.reporting.internal;

import com.seastella.fleet.api.Criticality;
import com.seastella.fleet.api.FleetMetrics;
import com.seastella.fleet.api.VesselStatus;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.invoice.api.InvoiceMetrics;
import com.seastella.invoice.api.InvoiceStatus;
import com.seastella.maintenance.api.MaintenanceMetrics;
import com.seastella.reporting.api.DashboardCommon.Distribution;
import com.seastella.reporting.api.DashboardCommon.Kpi;
import com.seastella.reporting.api.DashboardCommon.Slice;
import com.seastella.reporting.api.TechnicalHeadDashboard;
import com.seastella.servicerequest.api.ServiceRequestMetrics;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fleet health across one organization (SoW s8.1).
 *
 * <p>The vessel set comes from the resolved scope, so a Technical Head sees
 * their own organization's fleet and nothing else. The per-vessel table is
 * assembled from three grouped queries rather than one query per vessel: with
 * forty vessels the naive shape would be 120 round trips.
 */
@Service
class TechnicalHeadDashboardService {

    private static final int DUE_WINDOW_DAYS = 15;
    private static final int LIST_LIMIT = 25;

    private final DashboardSupport support;
    private final FleetMetrics fleet;
    private final MaintenanceMetrics maintenance;
    private final ServiceRequestMetrics requests;
    private final InvoiceMetrics invoices;

    TechnicalHeadDashboardService(DashboardSupport support, FleetMetrics fleet,
                                  MaintenanceMetrics maintenance, ServiceRequestMetrics requests,
                                  InvoiceMetrics invoices) {
        this.support = support;
        this.fleet = fleet;
        this.maintenance = maintenance;
        this.requests = requests;
        this.invoices = invoices;
    }

    @Transactional(readOnly = true)
    public TechnicalHeadDashboard build() {
        AccessScope scope = support.requireRole(Role.TECHNICAL_HEAD);
        Set<Long> vesselIds = scope.vesselIds();

        Map<VesselStatus, Long> vesselStatus = fleet.vesselStatusBreakdown(vesselIds);
        Map<ServiceRequestStatus, Long> requestCounts = requests.countsByStatus(vesselIds);

        long spares = fleet.spareCount(vesselIds);
        long overdue = maintenance.overdueCount(vesselIds);
        long dueSoon = maintenance.dueSoonCount(vesselIds, DUE_WINDOW_DAYS);
        long attention = maintenance.attentionCount(vesselIds);
        long openRequests = requests.openCount(vesselIds);
        long awaitingApproval = requests.countByStatuses(vesselIds,
                List.of(ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL));
        long shortages = fleet.partShortageCount(vesselIds);

        List<Kpi> kpis = List.of(
                Kpi.of("vessels", "Vessels", vesselIds.size()),
                Kpi.of("spares", "Spares", spares),
                Kpi.of("attention", "Spares needing attention", attention),
                Kpi.of("overdue", "Services overdue", overdue),
                Kpi.of("dueSoon", "Due within 15 days", dueSoon),
                Kpi.of("openRequests", "Open requests", openRequests),
                Kpi.of("awaitingApproval", "Awaiting Ship Manager approval", awaitingApproval),
                Kpi.of("partShortages", "Parts below minimum", shortages));

        InvoiceMetrics.Aggregate pending = invoices.summary(vesselIds).getOrDefault(
                InvoiceStatus.RAISED, new InvoiceMetrics.Aggregate(0, BigDecimal.ZERO));
        InvoiceMetrics.Aggregate accepted = invoices.summary(vesselIds).getOrDefault(
                InvoiceStatus.ACCEPTED, new InvoiceMetrics.Aggregate(0, BigDecimal.ZERO));

        return new TechnicalHeadDashboard(
                support.meta(scope, organizationName(vesselIds)),
                kpis,
                PlatformAdminDashboardService.vesselStatusDistribution(vesselStatus),
                support.dueDistribution(maintenance.dueBreakdown(vesselIds)),
                support.requestStageDistribution(requestCounts),
                criticalityDistribution(fleet.spareCriticalityBreakdown(vesselIds)),
                requests.resolutionSplit(vesselIds),
                vesselRows(vesselIds),
                maintenance.overdue(vesselIds, LIST_LIMIT),
                maintenance.dueSoon(vesselIds, DUE_WINDOW_DAYS, LIST_LIMIT),
                new TechnicalHeadDashboard.InvoiceRollup(
                        pending.count(), pending.total(),
                        accepted.count(), accepted.total(), "USD"),
                requests.recentActivity(vesselIds, 20));
    }

    /** Three grouped queries, then a join in memory - not one query per vessel. */
    private List<TechnicalHeadDashboard.VesselHealthRow> vesselRows(Set<Long> vesselIds) {
        List<FleetMetrics.VesselSummary> summaries = fleet.vesselSummaries(vesselIds);
        Map<Long, MaintenanceMetrics.VesselDueCounts> due = maintenance.perVessel(vesselIds);
        Map<Long, Long> openByVessel = requests.openCountPerVessel(vesselIds);

        List<TechnicalHeadDashboard.VesselHealthRow> rows = new ArrayList<>();
        for (FleetMetrics.VesselSummary v : summaries) {
            MaintenanceMetrics.VesselDueCounts d = due.getOrDefault(v.id(),
                    new MaintenanceMetrics.VesselDueCounts(0, 0, 0));

            rows.add(new TechnicalHeadDashboard.VesselHealthRow(
                    v.id(), v.name(), v.imoNumber(), v.vesselType(), v.status().name(),
                    v.spareCount(), d.dueSoon(), d.overdue(),
                    openByVessel.getOrDefault(v.id(), 0L),
                    fleet.partShortageCount(Set.of(v.id()))));
        }
        return rows;
    }

    private String organizationName(Set<Long> vesselIds) {
        List<FleetMetrics.VesselSummary> s = fleet.vesselSummaries(vesselIds);
        return s.isEmpty() ? null : s.get(0).organizationName();
    }

    private static Distribution criticalityDistribution(Map<Criticality, Long> counts) {
        List<Slice> slices = new ArrayList<>();
        for (Criticality c : Criticality.values()) {
            slices.add(new Slice(c.name(), label(c), counts.getOrDefault(c, 0L),
                    c == Criticality.CRITICAL ? "critical" : "neutral"));
        }
        return Distribution.of("spareCriticality", "Spares by criticality", slices);
    }

    private static String label(Criticality c) {
        return switch (c) {
            case CRITICAL -> "Critical";
            case HIGH -> "High";
            case MEDIUM -> "Medium";
            case LOW -> "Low";
        };
    }
}
