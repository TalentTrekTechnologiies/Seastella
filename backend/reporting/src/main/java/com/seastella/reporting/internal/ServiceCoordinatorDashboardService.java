package com.seastella.reporting.internal;

import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.invoice.api.InvoiceMetrics;
import com.seastella.reporting.api.DashboardCommon.ActionQueue;
import com.seastella.reporting.api.DashboardCommon.Kpi;
import com.seastella.reporting.api.ServiceCoordinatorDashboard;
import com.seastella.servicerequest.api.ServiceRequestMetrics;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * The service operations pipeline (SoW s8.4).
 *
 * <p>Every queue below is a stage of the s6 workflow, in order. The two worth
 * pointing at:
 *
 * <ul>
 *   <li>{@code acceptedReadyToAssign} is the only queue from which assignment
 *       is permitted - it is sourced from accepted invoices whose request has
 *       no engineer yet, which is the gate expressed as work rather than as a
 *       rule.</li>
 *   <li>{@code completionReportsPendingRelay} exists because the Coordinator is
 *       the Ship Manager's <em>only</em> channel for completion (s6.3). An
 *       unrelayed report is a Ship Manager who has not been told the job is
 *       done, and without this queue nothing would surface that.</li>
 * </ul>
 */
@Service
class ServiceCoordinatorDashboardService {

    private static final int LIST_LIMIT = 25;

    private final DashboardSupport support;
    private final ServiceRequestMetrics requests;
    private final InvoiceMetrics invoices;

    ServiceCoordinatorDashboardService(DashboardSupport support,
                                       ServiceRequestMetrics requests,
                                       InvoiceMetrics invoices) {
        this.support = support;
        this.requests = requests;
        this.invoices = invoices;
    }

    @Transactional(readOnly = true)
    public ServiceCoordinatorDashboard build() {
        AccessScope scope = support.requireRole(Role.SERVICE_COORDINATOR);
        Set<Long> vesselIds = scope.vesselIds();

        List<ServiceRequestMetrics.RequestSummary> incoming = requests.queue(vesselIds,
                List.of(ServiceRequestStatus.REPORTED, ServiceRequestStatus.TROUBLESHOOTING),
                LIST_LIMIT);

        List<ServiceRequestMetrics.RequestSummary> liveAgent = requests.queue(vesselIds,
                List.of(ServiceRequestStatus.LIVE_AGENT_ESCALATED), LIST_LIMIT);

        List<ServiceRequestMetrics.RequestSummary> triage = requests.queue(vesselIds,
                List.of(ServiceRequestStatus.OPERATIONALLY_APPROVED), LIST_LIMIT);

        List<InvoiceMetrics.InvoiceSummary> pendingInvoices =
                invoices.pendingAcceptance(vesselIds, LIST_LIMIT);

        List<InvoiceMetrics.InvoiceSummary> readyToAssign =
                invoices.acceptedAwaitingAssignment(vesselIds, LIST_LIMIT);

        List<ServiceRequestMetrics.RequestSummary> assigned = requests.queue(vesselIds,
                List.of(ServiceRequestStatus.ENGINEER_ASSIGNED, ServiceRequestStatus.IN_PROGRESS),
                LIST_LIMIT);

        List<ServiceRequestMetrics.RequestSummary> pendingRelay = requests.queue(vesselIds,
                List.of(ServiceRequestStatus.COMPLETION_REPORTED), LIST_LIMIT);

        ServiceRequestMetrics.ResolutionSplit split = requests.resolutionSplit(vesselIds);

        List<Kpi> kpis = List.of(
                Kpi.of("incoming", "Incoming requests", incoming.size()),
                Kpi.of("liveAgent", "Live chats needing a reply", liveAgent.size()),
                Kpi.of("awaitingTriage", "Awaiting triage", triage.size()),
                Kpi.of("invoicesPending", "Invoices awaiting acceptance", pendingInvoices.size()),
                Kpi.of("readyToAssign", "Accepted, ready to assign", readyToAssign.size()),
                Kpi.of("inProgress", "Assigned or in progress", assigned.size()),
                Kpi.of("pendingRelay", "Reports to relay", pendingRelay.size()),
                Kpi.of("resolvedNoCost", "Resolved without cost", split.resolvedWithoutCost()));

        return new ServiceCoordinatorDashboard(
                support.meta(scope, null),
                kpis,
                new ActionQueue<>("incoming", "Incoming service requests",
                        "Review the troubleshooting log", incoming.size(), incoming, null),
                new ActionQueue<>("liveAgentActive", "Live agent conversations",
                        "Reply to the Captain", liveAgent.size(), liveAgent, null),
                new ActionQueue<>("awaitingTriage", "Approved, awaiting triage",
                        "Close without cost, or raise an invoice", triage.size(), triage, null),
                new ActionQueue<>("invoicesPendingAcceptance", "Invoices sent to the Ship Manager",
                        "Awaiting their decision", pendingInvoices.size(), pendingInvoices,
                        "Waiting on the Ship Manager - no action available here."),
                new ActionQueue<>("acceptedReadyToAssign", "Accepted invoices",
                        "Assign a Service Engineer", readyToAssign.size(), readyToAssign, null),
                new ActionQueue<>("assignedInProgress", "Assigned jobs",
                        "Awaiting the engineer's completion report",
                        assigned.size(), assigned, null),
                new ActionQueue<>("completionReportsPendingRelay", "Completion reports received",
                        "Reconcile the cost and update the Ship Manager",
                        pendingRelay.size(), pendingRelay, null),
                split,
                new ServiceCoordinatorDashboard.Turnaround(
                        requests.averageTurnaroundHours(vesselIds, 30),
                        requests.averageTurnaroundHours(vesselIds, 90),
                        split.resolvedWithoutCost() + split.resolvedByEngineerVisit()),
                requests.recentActivity(vesselIds, 30));
    }
}
