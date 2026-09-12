package com.seastella.servicerequest.api;

/**
 * Port used by the state machine to confirm, independently of the request's own
 * status column, that an accepted invoice really exists.
 *
 * <p>Declared here and implemented in {@code invoice}, so the dependency runs
 * upward from invoice to service-request and no cycle is introduced.
 *
 * <p><b>Why check at all, when the transition table already only permits
 * assignment from INVOICE_ACCEPTED?</b> Because the status column could be
 * moved by something other than the state machine - a data repair, a future
 * bulk operation, a migration. SoW section 18 makes "the system blocks
 * assignment on an unaccepted or rejected invoice" an acceptance criterion, so
 * the invariant is checked against the invoice records themselves and not only
 * against a denormalised status.
 */
public interface InvoiceGateQuery {

    /** True only if an invoice for this request is currently ACCEPTED. */
    boolean hasAcceptedInvoice(Long serviceRequestId);
}
