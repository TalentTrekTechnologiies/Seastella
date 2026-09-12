package com.seastella.invoice.internal;

import com.seastella.invoice.api.InvoiceStatus;
import com.seastella.servicerequest.api.InvoiceGateQuery;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The invoice gate, answered from the invoice records themselves.
 *
 * <p>This deliberately does not consult the service request's status column.
 * The state machine already restricts assignment to a source state of
 * {@code INVOICE_ACCEPTED}; this is the independent second opinion that keeps
 * the invariant true even if that column were moved by something other than the
 * state machine. SoW section 18 states the requirement as a property of the
 * system, not of one code path.
 */
@Component
class DefaultInvoiceGateQuery implements InvoiceGateQuery {

    private final InvoiceRepository invoices;

    DefaultInvoiceGateQuery(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasAcceptedInvoice(Long serviceRequestId) {
        if (serviceRequestId == null) {
            return false;
        }
        return invoices.existsByServiceRequestIdAndStatus(serviceRequestId, InvoiceStatus.ACCEPTED);
    }
}
