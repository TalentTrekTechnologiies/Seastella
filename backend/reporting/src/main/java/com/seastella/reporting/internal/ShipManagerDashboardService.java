package com.seastella.reporting.internal;

import com.seastella.fleet.api.FleetMetrics;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.invoice.api.InvoiceMetrics;
import com.seastella.maintenance.api.MaintenanceMetrics;
import com.seastella.reporting.api.DashboardCommon.ActionQueue;
import com.seastella.reporting.api.DashboardCommon.Kpi;
import com.seastella.reporting.api.ShipManagerDashboard;
import com.seastella.servicerequest.api.ServiceRequestMetrics;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assigned vessels only (SoW s8.2).
 *
 * <p>Two queues lead because both block other people: an unapproved request
 * stalls the Coordinator, and an unaccepted invoice stalls the engineer
 * dispatch entirely.
 */
@Service
class ShipManagerDashboardService {

    private static final int DUE_WINDOW_DAYS = 15;
    private static final int LIST_LIMIT = 25;

    private final DashboardSupport support;
    private final FleetMetrics fleet;
    private final MaintenanceMetrics maintenance;
    private final ServiceRequestMetrics requests;
    private final InvoiceMetrics invoices;

    ShipManagerDashboardService(DashboardSupport support, FleetMetrics fleet,
                                MaintenanceMetrics maintenance, ServiceRequestMetrics requests,
                                InvoiceMetrics invoices) {
        this.support = support;
        this.fleet = fleet;
        this.maintenance = maintenance;
        this.requests = requests;
        this.invoices = invoices;
    }

    @Transactional(readOnly = true)
    public ShipManagerDashboard build() {
        AccessScope scope = support.requireRole(Role.SHIP_MANAGER);
        Set<Long> vesselIds = scope.vesselIds();

        List<ServiceRequestMetrics.RequestSummary> awaitingApproval = requests.queue(
                vesselIds,
                List.of(ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL),
                LIST_LIMIT);

        List<InvoiceMetrics.InvoiceSummary> pendingInvoices =
                invoices.pendingAcceptance(vesselIds, LIST_LIMIT);

        List<ServiceRequestMetrics.RequestSummary> inFlight = requests.queue(
                vesselIds,
                List.of(ServiceRequestStatus.OPERATIONALLY_APPROVED,
                        ServiceRequestStatus.INVOICE_ACCEPTED,
                        ServiceRequestStatus.ENGINEER_ASSIGNED,
                        ServiceRequestStatus.IN_PROGRESS,
                        ServiceRequestStatus.COMPLETION_REPORTED),
                LIST_LIMIT);

        long overdue = maintenance.overdueCount(vesselIds);
        long dueSoon = maintenance.dueSoonCount(vesselIds, DUE_WINDOW_DAYS);

        List<Kpi> kpis = List.of(
                Kpi.of("vessels", "Assigned vessels", vesselIds.size()),
                Kpi.of("awaitingApproval", "Requests awaiting my approval", awaitingApproval.size()),
                Kpi.of("invoicesPending", "Invoices awaiting my acceptance", pendingInvoices.size()),
                Kpi.of("openRequests", "Open requests", requests.openCount(vesselIds)),
                Kpi.of("overdue", "Services overdue", overdue),
                Kpi.of("dueSoon", "Due within 15 days", dueSoon),
                Kpi.of("partShortages", "Parts below minimum", fleet.partShortageCount(vesselIds)));

        return new ShipManagerDashboard(
                support.meta(scope, organizationName(vesselIds)),
                kpis,
                new ActionQueue<>("requestsAwaitingApproval",
                        "Service requests awaiting operational review",
                        "Approve, reject or request clarification",
                        awaitingApproval.size(), awaitingApproval, null),
                new ActionQueue<>("invoicesAwaitingAcceptance",
                        "Invoices awaiting acceptance",
                        "Accept, reject or query",
                        pendingInvoices.size(), pendingInvoices,
                        // The gate, stated where it is being held up.
                        "No Service Engineer is assigned until the invoice is accepted."),
                vesselRows(vesselIds),
                support.requestStageDistribution(requests.countsByStatus(vesselIds)),
                inFlight,
                maintenance.overdue(vesselIds, LIST_LIMIT),
                maintenance.dueSoon(vesselIds, DUE_WINDOW_DAYS, LIST_LIMIT),
                requests.recentActivity(vesselIds, 20));
    }

    private List<ShipManagerDashboard.VesselRow> vesselRows(Set<Long> vesselIds) {
        List<FleetMetrics.VesselSummary> summaries = fleet.vesselSummaries(vesselIds);
        Map<Long, MaintenanceMetrics.VesselDueCounts> due = maintenance.perVessel(vesselIds);
        Map<Long, Long> open = requests.openCountPerVessel(vesselIds);

        List<ShipManagerDashboard.VesselRow> rows = new ArrayList<>();
        for (FleetMetrics.VesselSummary v : summaries) {
            MaintenanceMetrics.VesselDueCounts d = due.getOrDefault(v.id(),
                    new MaintenanceMetrics.VesselDueCounts(0, 0, 0));
            rows.add(new ShipManagerDashboard.VesselRow(
                    v.id(), v.name(), v.imoNumber(), v.status().name(), v.spareCount(),
                    d.dueSoon(), d.overdue(), open.getOrDefault(v.id(), 0L),
                    fleet.partShortageCount(Set.of(v.id()))));
        }
        return rows;
    }

    private String organizationName(Set<Long> vesselIds) {
        List<FleetMetrics.VesselSummary> s = fleet.vesselSummaries(vesselIds);
        return s.isEmpty() ? null : s.get(0).organizationName();
    }
}
