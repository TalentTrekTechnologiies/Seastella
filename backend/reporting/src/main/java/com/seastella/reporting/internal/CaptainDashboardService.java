package com.seastella.reporting.internal;

import com.seastella.core.api.error.NotFoundException;
import com.seastella.fleet.api.Criticality;
import com.seastella.fleet.api.FleetDirectory;
import com.seastella.fleet.api.FleetMetrics;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.maintenance.api.MaintenanceMetrics;
import com.seastella.reporting.api.CaptainDashboard;
import com.seastella.reporting.api.DashboardCommon.Kpi;
import com.seastella.servicerequest.api.ServiceRequestMetrics;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One vessel, kept deliberately simple (SoW s8.3).
 *
 * <p><b>Nothing here touches InvoiceMetrics.</b> That is the enforcement: the
 * Captain's exclusion from cost data (SoW s12) is not a filter applied to a
 * richer payload, it is the absence of the dependency and the absence of any
 * field to put an amount in. A future change here cannot leak a figure that was
 * never fetched.
 */
@Service
class CaptainDashboardService {

    private static final int DUE_WINDOW_DAYS = 15;
    private static final int LIST_LIMIT = 25;

    private final DashboardSupport support;
    private final FleetMetrics fleet;
    private final MaintenanceMetrics maintenance;
    private final ServiceRequestMetrics requests;
    private final FleetDirectory directory;

    CaptainDashboardService(DashboardSupport support, FleetMetrics fleet,
                            MaintenanceMetrics maintenance, ServiceRequestMetrics requests,
                            FleetDirectory directory) {
        this.support = support;
        this.fleet = fleet;
        this.maintenance = maintenance;
        this.requests = requests;
        this.directory = directory;
    }

    @Transactional(readOnly = true)
    public CaptainDashboard build() {
        AccessScope scope = support.requireRole(Role.CAPTAIN);
        Set<Long> vesselIds = scope.vesselIds();

        if (vesselIds.isEmpty()) {
            // A captain with no assignment has no vessel to show, and must not
            // be able to infer that any other vessel exists.
            throw NotFoundException.ofResource("Vessel", "assigned");
        }

        Long vesselId = vesselIds.iterator().next();
        FleetMetrics.VesselSummary vessel = fleet.vesselSummary(vesselId);

        List<ServiceRequestMetrics.RequestSummary> mine = requests.queue(
                vesselIds, List.of(ServiceRequestStatus.values()), LIST_LIMIT);

        long criticalSpares = fleet.spareCriticalityBreakdown(vesselIds)
                .getOrDefault(Criticality.CRITICAL, 0L);
        long overdue = maintenance.overdueCount(vesselIds);
        long dueSoon = maintenance.dueSoonCount(vesselIds, DUE_WINDOW_DAYS);
        long open = requests.openCount(vesselIds);

        List<Kpi> kpis = List.of(
                Kpi.of("spares", "Spares onboard", vessel == null ? 0 : vessel.spareCount()),
                Kpi.of("criticalSpares", "Critical spares", criticalSpares),
                Kpi.of("openRequests", "My open requests", open),
                Kpi.of("overdue", "Services overdue", overdue),
                Kpi.of("dueSoon", "Due within 15 days", dueSoon));

        return new CaptainDashboard(
                support.meta(scope, vessel == null ? null : vessel.organizationName()),
                vesselCard(vessel, criticalSpares),
                kpis,
                myRequests(mine),
                maintenance.overdue(vesselIds, LIST_LIMIT),
                maintenance.dueSoon(vesselIds, DUE_WINDOW_DAYS, LIST_LIMIT),
                runningHours(vesselId),
                fleet.partShortages(vesselIds, LIST_LIMIT),
                requests.recentActivity(vesselIds, 20));
    }

    private static CaptainDashboard.VesselCard vesselCard(FleetMetrics.VesselSummary v,
                                                          long criticalSpares) {
        if (v == null) return null;
        return new CaptainDashboard.VesselCard(
                v.id(), v.name(), v.imoNumber(), null, v.flag(), v.vesselType(),
                v.status().name(), v.spareCount(), criticalSpares);
    }

    /**
     * Maps to the Captain's view of a request. {@code invoiceAccepted} is
     * derived from the workflow position, not from any invoice record - the
     * Captain learns that a cost was agreed, never what it was.
     */
    private static List<CaptainDashboard.MyRequest> myRequests(
            List<ServiceRequestMetrics.RequestSummary> summaries) {

        List<CaptainDashboard.MyRequest> out = new ArrayList<>();
        for (ServiceRequestMetrics.RequestSummary r : summaries) {
            ServiceRequestStatus s = r.status();

            boolean pastAcceptance = switch (s) {
                case INVOICE_ACCEPTED, ENGINEER_ASSIGNED, IN_PROGRESS,
                     COMPLETION_REPORTED, COMPLETED -> true;
                default -> false;
            };
            boolean engineerAssigned = switch (s) {
                case ENGINEER_ASSIGNED, IN_PROGRESS, COMPLETION_REPORTED, COMPLETED -> true;
                default -> false;
            };

            out.add(new CaptainDashboard.MyRequest(
                    r.id(), r.requestNumber(), r.spareId(), r.spareName(), r.sparePath(),
                    r.title(), r.priority().name(), s.name(), s.label(),
                    s == ServiceRequestStatus.CLARIFICATION_REQUESTED,
                    pastAcceptance, engineerAssigned, r.assignedEngineerName(),
                    r.raisedAt(), r.ageDays()));
        }
        return out;
    }

    private List<CaptainDashboard.RunningHourEntry> runningHours(Long vesselId) {
        List<CaptainDashboard.RunningHourEntry> out = new ArrayList<>();
        for (FleetMetrics.SpareNode s : fleet.spareTree(vesselId)) {
            if (s.tracksRunningHours()) {
                // Date of the last reading taken aboard; null until the first one.
                java.time.LocalDate lastReading = directory.hourReadings(s.id(), 1).stream()
                        .map(FleetDirectory.HourReading::readingDate).findFirst().orElse(null);
                out.add(new CaptainDashboard.RunningHourEntry(
                        s.id(), s.name(), s.path(), s.categoryCode(), s.runningHours(), lastReading));
            }
        }
        return out;
    }
}
