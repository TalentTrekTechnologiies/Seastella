package com.seastella.servicerequest.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The invoices raised against a request, for the request detail view.
 *
 * <p>Declared here and implemented by the invoice module, the same way as
 * {@link InvoiceGateQuery}, so the dependency still runs invoice → service
 * request. Callers must only show these to roles allowed to see invoice values
 * (Platform Admin, Technical Head, Ship Manager, Service Coordinator).
 */
public interface InvoiceLookup {

    List<InvoiceView> forRequest(Long serviceRequestId);

    record InvoiceView(
            Long id,
            String invoiceNumber,
            String amount,
            String currency,
            String description,
            String status,
            String statusLabel,
            Long raisedByUserId,
            String raisedByName,
            Instant raisedAt,
            Long decidedByUserId,
            String decidedByName,
            Instant decidedAt,
            String decisionNote,
            Payment payment) {}

    /**
     * Where the money stands: terms, received, outstanding and whether the
     * advance is in or the balance is late. A record, not a gate - nothing in
     * the workflow waits on it.
     */
    record Payment(
            int advancePercent,
            String advanceAmount,
            LocalDate dueDate,
            String received,
            String balance,
            boolean advanceReceived,
            String status,
            boolean overdue,
            List<PaymentLine> lines) {}

    record PaymentLine(Long id, String amount, LocalDate receivedOn, String method, String reference,
                       String note, String recordedByName, Instant recordedAt) {}
}
