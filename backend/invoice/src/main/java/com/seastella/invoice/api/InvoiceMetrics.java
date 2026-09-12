package com.seastella.invoice.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Invoice aggregates for the dashboards.
 *
 * <p>Nothing here is reachable by a Captain or a Service Engineer. SoW s12
 * restricts invoice and cost data to the Ship Manager, Service Coordinator,
 * Technical Head and Platform Admin, and the dashboard services enforce that
 * before calling any of these - the Captain and Engineer DTOs have no field for
 * an amount to leak into.
 */
public interface InvoiceMetrics {

    Map<InvoiceStatus, Aggregate> summary(Set<Long> vesselIds);

    Map<InvoiceStatus, Aggregate> summaryPlatformWide();

    /** Invoices the Ship Manager still owes a decision on. */
    List<InvoiceSummary> pendingAcceptance(Set<Long> vesselIds, int limit);

    /**
     * Accepted invoices whose request has no engineer yet: the Coordinator's
     * "ready to assign" queue, and the only state from which assignment is
     * permitted.
     */
    List<InvoiceSummary> acceptedAwaitingAssignment(Set<Long> vesselIds, int limit);

    long countByStatus(Set<Long> vesselIds, InvoiceStatus status);

    BigDecimal totalByStatus(Set<Long> vesselIds, InvoiceStatus status);

    /** The accepted invoice for a request, if any. Used for cost reconciliation. */
    InvoiceSummary acceptedFor(Long serviceRequestId);

    List<InvoiceSummary> forServiceRequest(Long serviceRequestId);

    record Aggregate(long count, BigDecimal total) {}

    record InvoiceSummary(
            Long id, String invoiceNumber, Long serviceRequestId, String requestNumber,
            Long vesselId, String vesselName, BigDecimal amount, String currency,
            String description, InvoiceStatus status, String statusLabel,
            Long raisedByUserId, String raisedByName,
            Long decidedByUserId, String decidedByName, String decisionNote,
            Instant raisedAt, Instant decidedAt) {}
}
