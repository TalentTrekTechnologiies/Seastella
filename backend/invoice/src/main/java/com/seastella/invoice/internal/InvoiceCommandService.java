package com.seastella.invoice.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
import com.seastella.core.api.event.DomainEventPublisher;
import com.seastella.fleet.api.FleetDirectory;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.invoice.api.InvoiceEvents;
import com.seastella.invoice.api.InvoiceStatus;
import com.seastella.servicerequest.api.ServiceRequestAction;
import com.seastella.servicerequest.api.ServiceRequestCommands;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * Raising and deciding invoices.
 *
 * <p>An invoice and its request move together or not at all: each method
 * applies the request transition and writes the invoice record in one
 * transaction. The transition runs first, so the state machine's checks —
 * scope, role, the request being in the right state, a reason where one is
 * required — decide whether the invoice change happens at all.
 */
@Service
class InvoiceCommandService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final Set<String> CURRENCIES = Set.of("USD", "EUR", "GBP", "SGD", "INR", "NOK");

    private final InvoiceRepository invoices;
    private final ServiceRequestCommands requests;
    private final FleetDirectory fleet;
    private final ScopeResolver scopeResolver;
    private final DomainEventPublisher events;
    private final AuditService audit;

    InvoiceCommandService(InvoiceRepository invoices, ServiceRequestCommands requests,
                          FleetDirectory fleet, ScopeResolver scopeResolver, DomainEventPublisher events,
                          AuditService audit) {
        this.invoices = invoices;
        this.requests = requests;
        this.fleet = fleet;
        this.scopeResolver = scopeResolver;
        this.events = events;
        this.audit = audit;
    }

    /** Service Coordinator raises an invoice for an approved request. */
    @Transactional
    public Long raise(Long serviceRequestId, BigDecimal amount, String currency, String description) {
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("Enter an invoice amount greater than zero.");
        }
        if (amount.scale() > 2) {
            throw new ValidationException("Amounts have at most two decimal places.");
        }
        String code = currency == null || currency.isBlank() ? "USD" : currency.trim().toUpperCase();
        if (!CURRENCIES.contains(code)) {
            throw new ValidationException("Currency must be one of " + String.join(", ", CURRENCIES) + ".");
        }
        String text = description == null ? "" : description.trim();
        if (text.isEmpty()) {
            throw new ValidationException("Describe what the invoice covers.");
        }

        ServiceRequestCommands.Placement placement = requests.placementInScope(serviceRequestId);

        // Earlier invoice this one replaces, if the last was rejected or queried.
        List<Invoice> previous = invoices.findByServiceRequestIdOrderByCreatedAtDesc(serviceRequestId);
        Long supersedes = previous.stream()
                .filter(i -> i.getStatus() == InvoiceStatus.REJECTED || i.getStatus() == InvoiceStatus.QUERIED)
                .map(Invoice::getId)
                .findFirst()
                .orElse(null);

        requests.transition(serviceRequestId, ServiceRequestAction.RAISE_INVOICE, null, null);

        AccessScope scope = scopeResolver.currentScope();
        String orgCode = fleet.organizationCodeForVessel(placement.vesselId()).orElse("ORG");
        Invoice invoice = new Invoice(nextInvoiceNumber(orgCode), serviceRequestId,
                placement.organizationId(), placement.vesselId(), scope.userId(), amount, code, text);
        invoice.setSupersedesInvoiceId(supersedes);
        invoices.save(invoice);

        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role() == null ? null : scope.role().name())
                .action(AuditAction.INVOICE_RAISED)
                .entity("Invoice", invoice.getId())
                .scope(placement.organizationId(), placement.vesselId())
                .after(AuditJson.of("invoiceNumber", invoice.getInvoiceNumber(),
                        "serviceRequestId", serviceRequestId, "amount", amount.toPlainString(),
                        "currency", code, "description", text, "supersedesInvoiceId", supersedes))
                .build());
        return invoice.getId();
    }

    /** Ship Manager accepts, rejects or queries the open invoice. */
    @Transactional
    public Long decide(Long invoiceId, Decision decision, String note) {
        AccessScope scope = scopeResolver.currentScope();
        Invoice invoice = invoices.findById(invoiceId)
                .orElseThrow(() -> NotFoundException.ofResource("Invoice", invoiceId));
        if (!scope.permitsVessel(invoice.getVesselId())) {
            throw NotFoundException.ofResource("Invoice", invoiceId);
        }
        if (invoice.getStatus() != InvoiceStatus.RAISED) {
            throw new WorkflowException("Invoice " + invoice.getInvoiceNumber() + " is "
                    + invoice.getStatus().label().toLowerCase() + " and cannot be decided again.");
        }

        String trimmed = note == null || note.isBlank() ? null : note.trim();
        requests.transition(invoice.getServiceRequestId(), decision.action, trimmed, null);

        Instant now = Instant.now();
        switch (decision) {
            case ACCEPT -> invoice.accept(scope.userId(), trimmed, now);
            case REJECT -> invoice.reject(scope.userId(), trimmed, now);
            case QUERY -> invoice.query(scope.userId(), trimmed, now);
        }
        invoices.save(invoice);

        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role() == null ? null : scope.role().name())
                .action(switch (decision) {
                    case ACCEPT -> AuditAction.INVOICE_ACCEPTED;
                    case REJECT -> AuditAction.INVOICE_REJECTED;
                    case QUERY -> AuditAction.INVOICE_QUERIED;
                })
                .entity("Invoice", invoice.getId())
                .scope(invoice.getOrganizationId(), invoice.getVesselId())
                .before(AuditJson.of("status", InvoiceStatus.RAISED.name()))
                .after(AuditJson.of("status", invoice.getStatus().name(), "note", trimmed))
                .occurredAt(now)
                .build());

        events.publish(new InvoiceEvents.InvoiceDecided(
                invoice.getId(), invoice.getInvoiceNumber(), invoice.getServiceRequestId(),
                invoice.getStatus(), invoice.getAmount(), invoice.getCurrency(),
                scope.userId(), invoice.getOrganizationId(), invoice.getVesselId(), now));
        return invoice.getServiceRequestId();
    }

    private String nextInvoiceNumber(String orgCode) {
        String prefix = "INV-" + orgCode + "-" + LocalDate.now(ZoneOffset.UTC).format(MONTH) + "-";
        long seq = invoices.countByInvoiceNumberStartingWith(prefix) + 1;
        String number = prefix + String.format("%04d", seq);
        while (invoices.existsByInvoiceNumber(number)) {
            seq++;
            number = prefix + String.format("%04d", seq);
        }
        return number;
    }

    enum Decision {
        ACCEPT(ServiceRequestAction.ACCEPT_INVOICE),
        REJECT(ServiceRequestAction.REJECT_INVOICE),
        QUERY(ServiceRequestAction.QUERY_INVOICE);

        final ServiceRequestAction action;

        Decision(ServiceRequestAction action) {
            this.action = action;
        }
    }
}
