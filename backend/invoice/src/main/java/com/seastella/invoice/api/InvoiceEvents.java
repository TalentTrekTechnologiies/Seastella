package com.seastella.invoice.api;

import com.seastella.core.api.event.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;

/** Domain events published by the invoice module. */
public final class InvoiceEvents {

    private InvoiceEvents() {}

    /**
     * An accepted invoice still has money owing after its due date. Raised
     * once per due date by the nightly scan; it informs, it stops nothing.
     */
    public record PaymentOverdue(
            Long invoiceId,
            String invoiceNumber,
            Long serviceRequestId,
            String requestNumber,
            String vesselName,
            BigDecimal amount,
            BigDecimal balance,
            String currency,
            java.time.LocalDate dueDate,
            Long organizationId,
            Long vesselId,
            Instant occurredAt) implements DomainEvent {

        public static final String EVENT_TYPE = "INVOICE_PAYMENT_OVERDUE";

        @Override
        public String eventType() { return EVENT_TYPE; }

        @Override
        public String summary() {
            return "Payment overdue on invoice " + invoiceNumber;
        }
    }

    public record InvoiceDecided(
            Long invoiceId,
            String invoiceNumber,
            Long serviceRequestId,
            InvoiceStatus status,
            BigDecimal amount,
            String currency,
            Long actorUserId,
            Long organizationId,
            Long vesselId,
            Instant occurredAt) implements DomainEvent {

        @Override
        public String eventType() { return "INVOICE_" + status.name(); }

        @Override
        public String summary() {
            // Amount is intentionally omitted: the activity feed is read by the
            // Platform Admin, but the same summary string is reused elsewhere.
            return "Invoice " + invoiceNumber + " " + status.label().toLowerCase();
        }
    }
}
