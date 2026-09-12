package com.seastella.reporting.internal;

import com.seastella.core.api.error.ForbiddenException;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.maintenance.api.DueStatus;
import com.seastella.reporting.api.DashboardCommon.Distribution;
import com.seastella.reporting.api.DashboardCommon.Meta;
import com.seastella.reporting.api.DashboardCommon.Slice;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared scope and shaping helpers for the dashboard services.
 *
 * <p>{@link #requireRole} is the single place a dashboard confirms who is
 * asking. Method security on the controller already restricts each endpoint,
 * but a service that trusts its caller is one refactor away from being reachable
 * from somewhere that does not check - so the check is repeated where the data
 * is actually assembled.
 */
@Component
class DashboardSupport {

    private final ScopeResolver scopeResolver;

    DashboardSupport(ScopeResolver scopeResolver) {
        this.scopeResolver = scopeResolver;
    }

    AccessScope scope() {
        return scopeResolver.currentScope();
    }

    /** @throws ForbiddenException if the caller does not hold the expected role */
    AccessScope requireRole(Role expected) {
        AccessScope scope = scopeResolver.currentScope();
        if (scope.role() != expected) {
            throw ForbiddenException.ofAction("view the " + expected.name() + " dashboard");
        }
        return scope;
    }

    Meta meta(AccessScope scope, String organizationName) {
        return new Meta(
                scope.role() == null ? null : scope.role().name(),
                scope.kind().name(),
                scope.organizationId(),
                organizationName,
                scope.vesselIds().size(),
                Instant.now(),
                scope.canSeeFinancials());
    }

    /** Colour comes from the engine's own bands, never re-decided here. */
    Distribution dueDistribution(Map<DueStatus, Long> counts) {
        List<Slice> slices = new ArrayList<>();
        for (DueStatus s : List.of(DueStatus.NORMAL, DueStatus.APPROACHING,
                DueStatus.URGENT, DueStatus.DUE, DueStatus.OVERDUE)) {
            slices.add(new Slice(s.name(), s.label(), counts.getOrDefault(s, 0L), s.colour()));
        }
        return Distribution.of("spareHealth", "Spare maintenance health", slices);
    }

    /**
     * Requests grouped into the workflow stages SoW s8.1 names, rather than one
     * slice per raw status - fifteen slices would be a legend, not a chart.
     */
    Distribution requestStageDistribution(Map<ServiceRequestStatus, Long> counts) {
        long troubleshooting = sum(counts, ServiceRequestStatus.REPORTED,
                ServiceRequestStatus.TROUBLESHOOTING);
        long liveAgent = sum(counts, ServiceRequestStatus.LIVE_AGENT_ESCALATED);
        long awaitingApproval = sum(counts, ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL,
                ServiceRequestStatus.CLARIFICATION_REQUESTED);
        long invoicePending = sum(counts, ServiceRequestStatus.OPERATIONALLY_APPROVED,
                ServiceRequestStatus.INVOICE_RAISED, ServiceRequestStatus.INVOICE_QUERIED,
                ServiceRequestStatus.INVOICE_REJECTED);
        long invoiceAccepted = sum(counts, ServiceRequestStatus.INVOICE_ACCEPTED);
        long assigned = sum(counts, ServiceRequestStatus.ENGINEER_ASSIGNED,
                ServiceRequestStatus.IN_PROGRESS, ServiceRequestStatus.COMPLETION_REPORTED);
        long closed = sum(counts, ServiceRequestStatus.COMPLETED,
                ServiceRequestStatus.CLOSED_NO_COST, ServiceRequestStatus.REJECTED);

        return Distribution.of("requestsByStage", "Service requests by stage", List.of(
                new Slice("troubleshooting", "Troubleshooting", troubleshooting, "accent"),
                new Slice("liveAgent", "Live agent", liveAgent, "accent"),
                new Slice("awaitingApproval", "Awaiting approval", awaitingApproval, "yellow"),
                new Slice("invoicePending", "Invoice pending", invoicePending, "orange"),
                new Slice("invoiceAccepted", "Invoice accepted", invoiceAccepted, "accent"),
                new Slice("assigned", "Assigned / in progress", assigned, "accent"),
                new Slice("closed", "Closed", closed, "green")));
    }

    static long sum(Map<ServiceRequestStatus, Long> counts, ServiceRequestStatus... statuses) {
        long total = 0;
        for (ServiceRequestStatus s : statuses) {
            total += counts.getOrDefault(s, 0L);
        }
        return total;
    }
}
