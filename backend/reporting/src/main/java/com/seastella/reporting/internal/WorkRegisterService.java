package com.seastella.reporting.internal;

import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.UserDirectory;
import com.seastella.invoice.api.InvoiceMetrics;
import com.seastella.invoice.api.InvoiceMetrics.InvoiceSummary;
import com.seastella.invoice.api.InvoiceStatus;
import com.seastella.servicerequest.api.ServiceRequestMetrics;
import com.seastella.servicerequest.api.ServiceRequestMetrics.RequestSummary;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The registers behind three sidebar pages: every invoice a person may see,
 * the Coordinator's engineers and what each is working on, and an engineer's
 * own finished jobs.
 *
 * <p>Scope is resolved here as on every dashboard. Invoice amounts never reach
 * the Captain or the Service Engineer (SoW s8): the invoice register refuses
 * them, and an engineer's job history carries the work, never the cost.
 */
@Service
class WorkRegisterService {

    private static final int INVOICE_LIMIT = 500;
    private static final int HISTORY_LIMIT = 200;

    private static final Set<ServiceRequestStatus> ACTIVE_JOB = EnumSet.of(
            ServiceRequestStatus.ENGINEER_ASSIGNED, ServiceRequestStatus.IN_PROGRESS);
    private static final Set<ServiceRequestStatus> FINISHED_JOB = EnumSet.of(
            ServiceRequestStatus.COMPLETION_REPORTED, ServiceRequestStatus.COMPLETED);

    private final DashboardSupport support;
    private final InvoiceMetrics invoices;
    private final ServiceRequestMetrics requests;
    private final UserDirectory users;

    WorkRegisterService(DashboardSupport support, InvoiceMetrics invoices,
                        ServiceRequestMetrics requests, UserDirectory users) {
        this.support = support;
        this.invoices = invoices;
        this.requests = requests;
        this.users = users;
    }

    // ---------------------------------------------------------------- invoices

    @Transactional(readOnly = true)
    InvoiceRegister invoices() {
        AccessScope scope = support.scope();
        List<InvoiceSummary> found = scope.isPlatformWide()
                ? invoices.all(INVOICE_LIMIT)
                : invoices.forVessels(scope.vesselIds(), INVOICE_LIMIT);

        Map<InvoiceStatus, List<InvoiceSummary>> byStatus = found.stream()
                .collect(Collectors.groupingBy(InvoiceSummary::status));
        List<StatusTotal> totals = EnumSet.allOf(InvoiceStatus.class).stream()
                .map(s -> {
                    List<InvoiceSummary> in = byStatus.getOrDefault(s, List.of());
                    return new StatusTotal(s.name(), in.size(),
                            in.stream().map(InvoiceSummary::amount).filter(Objects::nonNull)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add));
                })
                .toList();
        return new InvoiceRegister(scope.role() == Role.SHIP_MANAGER, totals, found);
    }

    // --------------------------------------------------------------- engineers

    /** Every active engineer, with the jobs they hold on the caller's vessels. */
    @Transactional(readOnly = true)
    List<EngineerLoad> engineers() {
        AccessScope scope = support.scope();
        return users.activeByRole(Role.SERVICE_ENGINEER).stream()
                .map(e -> {
                    List<RequestSummary> active = inScope(scope, requests.forEngineer(e.id(), ACTIVE_JOB));
                    List<RequestSummary> finished = inScope(scope, requests.forEngineer(e.id(), FINISHED_JOB));
                    Instant lastFinished = finished.stream().map(RequestSummary::lastUpdatedAt)
                            .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
                    return new EngineerLoad(e.id(), e.fullName(), e.email(),
                            active.stream().map(WorkRegisterService::job).toList(),
                            finished.size(), lastFinished);
                })
                .sorted(Comparator.comparingInt((EngineerLoad l) -> l.activeJobs().size())
                        .thenComparing(EngineerLoad::fullName))
                .toList();
    }

    // ------------------------------------------------------------- job history

    /** The engineer's own finished jobs, newest first, with what they reported. */
    @Transactional(readOnly = true)
    List<FinishedJob> jobHistory() {
        AccessScope scope = support.scope();
        return requests.forEngineer(scope.userId(), FINISHED_JOB).stream()
                .sorted(Comparator.comparing(RequestSummary::lastUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(HISTORY_LIMIT)
                .map(r -> {
                    ServiceRequestMetrics.CompletionSummary report = requests.completionReport(r.id());
                    // The work, never the cost: amounts are not the engineer's to see.
                    return new FinishedJob(job(r),
                            report == null ? null : report.workPerformed(),
                            report == null ? null : report.partsUsed(),
                            report == null ? null : report.outcome(),
                            report == null ? null : report.reportedAt(),
                            report == null ? null : report.reconciledAt());
                })
                .toList();
    }

    private static List<RequestSummary> inScope(AccessScope scope, List<RequestSummary> found) {
        if (scope.isPlatformWide()) return found;
        return found.stream().filter(r -> scope.permitsVessel(r.vesselId())).toList();
    }

    private static Job job(RequestSummary r) {
        return new Job(r.id(), r.requestNumber(), r.vesselName(), r.spareName(), r.title(),
                r.priority() == null ? null : r.priority().name(), r.status().name(), r.statusLabel(),
                r.raisedAt(), r.lastUpdatedAt());
    }

    record InvoiceRegister(boolean canDecide, List<StatusTotal> totals, List<InvoiceSummary> invoices) {}

    record StatusTotal(String status, long count, BigDecimal amount) {}

    record EngineerLoad(Long id, String fullName, String email, List<Job> activeJobs,
                        long completedJobs, Instant lastCompletedAt) {}

    record Job(Long requestId, String requestNumber, String vesselName, String spareName, String title,
               String priority, String status, String statusLabel, Instant raisedAt, Instant lastUpdatedAt) {}

    record FinishedJob(Job job, String workPerformed, String partsUsed, String outcome,
                       Instant reportedAt, Instant completedAt) {}
}
