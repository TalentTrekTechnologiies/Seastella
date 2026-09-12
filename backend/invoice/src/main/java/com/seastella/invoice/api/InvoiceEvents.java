package com.seastella.invoice.api;

import com.seastella.core.api.event.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;

/** Domain events published by the invoice module. */
public final class InvoiceEvents {

    private InvoiceEvents() {}

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
