package com.seastella.reporting.internal;

import com.seastella.fleet.api.FleetMetrics;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.reporting.api.DashboardCommon.Kpi;
import com.seastella.reporting.api.ServiceEngineerDashboard;
import com.seastella.servicerequest.api.ServiceRequestMetrics;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A job workspace (SoW s5, master brief s7.6).
 *
 * <p>Every query here is keyed on {@code engineerUserId}, never on a vessel
 * set. That is the whole isolation story: an engineer reaches a vessel only
 * through a job assigned to them, so there is no query in this class that could
 * return another engineer's work even if the vessel happened to be the same.
 *
 * <p>{@code InvoiceMetrics} is not a dependency - see the Captain service for
 * the same reasoning.
 */
@Service
class ServiceEngineerDashboardService {

    private static final List<ServiceRequestStatus> ACTIVE = List.of(
            ServiceRequestStatus.ENGINEER_ASSIGNED,
            ServiceRequestStatus.IN_PROGRESS,
            ServiceRequestStatus.COMPLETION_REPORTED);

    private final DashboardSupport support;
    private final ServiceRequestMetrics requests;
    private final FleetMetrics fleet;

    ServiceEngineerDashboardService(DashboardSupport support, ServiceRequestMetrics requests,
                                    FleetMetrics fleet) {
        this.support = support;
        this.requests = requests;
        this.fleet = fleet;
    }

    @Transactional(readOnly = true)
    public ServiceEngineerDashboard build() {
        AccessScope scope = support.requireRole(Role.SERVICE_ENGINEER);
        Long engineerId = scope.userId();

        List<ServiceRequestMetrics.RequestSummary> active =
                requests.forEngineer(engineerId, ACTIVE);
        List<ServiceRequestMetrics.RequestSummary> completed =
                requests.forEngineer(engineerId, List.of(ServiceRequestStatus.COMPLETED));

        List<ServiceEngineerDashboard.Job> assigned = new ArrayList<>();
        List<ServiceEngineerDashboard.Job> inProgress = new ArrayList<>();
        List<ServiceEngineerDashboard.Job> awaitingReport = new ArrayList<>();

        // Vessel identifiers fetched once for the whole job set rather than
        // per job - a handful of jobs would otherwise be a handful of queries.
        Map<Long, String> imoByVessel = imoByVessel(active);

        for (ServiceRequestMetrics.RequestSummary r : active) {
            ServiceEngineerDashboard.Job job = toJob(r, imoByVessel);
            switch (r.status()) {
                case ENGINEER_ASSIGNED -> assigned.add(job);
                case IN_PROGRESS -> inProgress.add(job);
                case COMPLETION_REPORTED -> awaitingReport.add(job);
                default -> { }
            }
        }

        // "Today" is anything assigned on or before today that is not yet done.
        LocalDate today = LocalDate.now();
        List<ServiceEngineerDashboard.Job> todays = new ArrayList<>();
        List<ServiceEngineerDashboard.Job> upcoming = new ArrayList<>();
        for (ServiceEngineerDashboard.Job job : assigned) {
            LocalDate assignedOn = job.assignedAt() == null
                    ? today
                    : job.assignedAt().atZone(ZoneOffset.UTC).toLocalDate();
            if (!assignedOn.isAfter(today)) {
                todays.add(job);
            } else {
                upcoming.add(job);
            }
        }

        List<Kpi> kpis = List.of(
                Kpi.of("today", "Jobs today", todays.size()),
                Kpi.of("upcoming", "Upcoming jobs", upcoming.size()),
                Kpi.of("inProgress", "In progress", inProgress.size()),
                Kpi.of("awaitingReview", "Reported, awaiting Coordinator", awaitingReport.size()),
                Kpi.of("completed", "Completed", completed.size()));

        return new ServiceEngineerDashboard(
                support.meta(scope, null),
                kpis,
                todays,
                upcoming,
                inProgress,
                awaitingReport,
                recentlyCompleted(completed));
    }

    private ServiceEngineerDashboard.Job toJob(ServiceRequestMetrics.RequestSummary r,
                                               Map<Long, String> imoByVessel) {
        FleetMetrics.SpareNode spare = fleet.spare(r.spareId());

        // invoiceAccepted is derived from workflow position, never from an
        // invoice record: the engineer needs to know the job is authorised,
        // not what it cost (SoW s12).
        boolean authorised = switch (r.status()) {
            case ENGINEER_ASSIGNED, IN_PROGRESS, COMPLETION_REPORTED, COMPLETED -> true;
            default -> false;
        };

        return new ServiceEngineerDashboard.Job(
                r.id(), r.requestNumber(), r.vesselId(), r.vesselName(),
                imoByVessel.get(r.vesselId()),
                r.spareId(), r.spareName(), r.sparePath(), r.categoryCode(),
                spare == null ? null : spare.make(),
                spare == null ? null : spare.model(),
                spare == null ? null : spare.serialNumber(),
                r.title(), null, r.priority().name(),
                r.status().name(), r.statusLabel(),
                authorised, r.raisedAt(), null, r.ageDays());
    }

    /** One query for every vessel the engineer has a job on. */
    private Map<Long, String> imoByVessel(List<ServiceRequestMetrics.RequestSummary> jobs) {
        Set<Long> vesselIds = jobs.stream()
                .map(ServiceRequestMetrics.RequestSummary::vesselId)
                .collect(Collectors.toSet());

        if (vesselIds.isEmpty()) {
            return Map.of();
        }
        return fleet.vesselSummaries(vesselIds).stream()
                .collect(Collectors.toMap(FleetMetrics.VesselSummary::id,
                        FleetMetrics.VesselSummary::imoNumber));
    }

    private List<ServiceEngineerDashboard.CompletedJob> recentlyCompleted(
            List<ServiceRequestMetrics.RequestSummary> completed) {

        List<ServiceEngineerDashboard.CompletedJob> out = new ArrayList<>();
        for (ServiceRequestMetrics.RequestSummary r : completed) {
            ServiceRequestMetrics.CompletionSummary report = requests.completionReport(r.id());
            out.add(new ServiceEngineerDashboard.CompletedJob(
                    r.id(), r.requestNumber(), r.vesselName(), r.spareName(),
                    report == null ? null : report.workPerformed(),
                    report == null ? null : report.outcome(),
                    null,
                    report == null ? null : report.reportedAt()));
        }
        return out;
    }
}
