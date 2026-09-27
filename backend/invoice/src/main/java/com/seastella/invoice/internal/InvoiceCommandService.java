package com.seastella.invoice.internal;

import com.seastella.core.api.time.BusinessTime;
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
    private final InvoicePaymentRepository payments;
    private final ServiceRequestCommands requests;
    private final FleetDirectory fleet;
    private final ScopeResolver scopeResolver;
    private final DomainEventPublisher events;
    private final AuditService audit;

    InvoiceCommandService(InvoiceRepository invoices, InvoicePaymentRepository payments,
                          ServiceRequestCommands requests,
                          FleetDirectory fleet, ScopeResolver scopeResolver, DomainEventPublisher events,
                          AuditService audit) {
        this.invoices = invoices;
        this.payments = payments;
        this.requests = requests;
        this.fleet = fleet;
        this.scopeResolver = scopeResolver;
        this.events = events;
        this.audit = audit;
    }

    /**
     * Service Coordinator raises an invoice for an approved request, with the
     * payment terms offered: the share wanted in advance and when the whole is
     * due. Both are optional - no advance, no deadline.
     */
    @Transactional
    public Long raise(Long serviceRequestId, BigDecimal amount, String currency, String description,
                      Integer advancePercent, LocalDate paymentDueDate) {
        int advance = checkedAdvance(advancePercent);
        checkedDueDate(paymentDueDate);
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
        invoice.setTerms(advance, paymentDueDate);
        invoices.save(invoice);

        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role() == null ? null : scope.role().name())
                .action(AuditAction.INVOICE_RAISED)
                .entity("Invoice", invoice.getId())
                .scope(placement.organizationId(), placement.vesselId())
                .after(AuditJson.of("invoiceNumber", invoice.getInvoiceNumber(),
                        "serviceRequestId", serviceRequestId, "amount", amount.toPlainString(),
                        "currency", code, "description", text, "supersedesInvoiceId", supersedes,
                        "advancePercent", advance, "paymentDueDate", paymentDueDate))
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

    // ------------------------------------------------------------- payments

    /** The Coordinator changes the terms - a later deadline, a different advance. */
    @Transactional
    public Long updateTerms(Long invoiceId, Integer advancePercent, LocalDate paymentDueDate) {
        AccessScope scope = scopeResolver.currentScope();
        Invoice invoice = inScope(invoiceId, scope);
        if (invoice.getStatus() == InvoiceStatus.SUPERSEDED || invoice.getStatus() == InvoiceStatus.REJECTED) {
            throw new WorkflowException("Invoice " + invoice.getInvoiceNumber() + " is "
                    + invoice.getStatus().label().toLowerCase() + "; its terms no longer apply.");
        }
        int advance = checkedAdvance(advancePercent);
        checkedDueDate(paymentDueDate);
        String before = AuditJson.of("advancePercent", invoice.getAdvancePercent(),
                "paymentDueDate", invoice.getPaymentDueDate());
        invoice.setTerms(advance, paymentDueDate);
        invoices.save(invoice);
        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role() == null ? null : scope.role().name())
                .action(AuditAction.INVOICE_TERMS_CHANGED)
                .entity("Invoice", invoice.getId())
                .scope(invoice.getOrganizationId(), invoice.getVesselId())
                .before(before)
                .after(AuditJson.of("advancePercent", advance, "paymentDueDate", paymentDueDate))
                .build());
        return invoice.getServiceRequestId();
    }

    /**
     * Money received through Seastella's own finance process, recorded against
     * an accepted invoice. Recording never blocks or starts anything: it tells
     * the people on the request where the payment stands.
     */
    @Transactional
    public Long recordPayment(Long invoiceId, BigDecimal amount, LocalDate receivedOn, String method,
                              String reference, String note) {
        AccessScope scope = scopeResolver.currentScope();
        Invoice invoice = inScope(invoiceId, scope);
        if (invoice.getStatus() != InvoiceStatus.ACCEPTED) {
            throw new WorkflowException("Payments are recorded against an accepted invoice; "
                    + invoice.getInvoiceNumber() + " is " + invoice.getStatus().label().toLowerCase() + ".");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("Enter the amount received.");
        }
        if (amount.scale() > 2) {
            throw new ValidationException("Amounts have at most two decimal places.");
        }
        BigDecimal outstanding = invoice.getAmount().subtract(payments.totalReceived(invoice.getId()));
        if (amount.compareTo(outstanding) > 0) {
            throw new ValidationException("That is more than the " + outstanding.toPlainString() + " "
                    + invoice.getCurrency() + " still due on this invoice.");
        }
        LocalDate on = receivedOn == null ? BusinessTime.today() : receivedOn;
        if (on.isAfter(BusinessTime.today().plusDays(1))) {
            throw new ValidationException("The date received cannot be in the future.");
        }
        InvoicePayment.Method how;
        try {
            how = InvoicePayment.Method.valueOf(method == null ? "BANK_TRANSFER" : method.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Choose how it was paid: bank transfer, cheque, cash, card or other.");
        }
        String ref = trimmed(reference, 120, "reference");
        String text = trimmed(note, 500, "note");

        InvoicePayment payment = payments.save(new InvoicePayment(invoice.getId(), amount, on, how, ref, text,
                scope.userId()));
        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role() == null ? null : scope.role().name())
                .action(AuditAction.INVOICE_PAYMENT_RECORDED)
                .entity("Invoice", invoice.getId())
                .scope(invoice.getOrganizationId(), invoice.getVesselId())
                .after(AuditJson.of("invoiceNumber", invoice.getInvoiceNumber(), "paymentId", payment.getId(),
                        "amount", amount.toPlainString(), "currency", invoice.getCurrency(),
                        "receivedOn", on, "method", how.name(), "reference", ref))
                .build());
        return invoice.getServiceRequestId();
    }

    /** A payment entered by mistake comes off again; the audit trail keeps both. */
    @Transactional
    public Long removePayment(Long invoiceId, Long paymentId) {
        AccessScope scope = scopeResolver.currentScope();
        Invoice invoice = inScope(invoiceId, scope);
        InvoicePayment payment = payments.findById(paymentId)
                .filter(p -> p.getInvoiceId().equals(invoice.getId()))
                .orElseThrow(() -> NotFoundException.ofResource("Payment", paymentId));
        payments.delete(payment);
        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role() == null ? null : scope.role().name())
                .action(AuditAction.INVOICE_PAYMENT_REMOVED)
                .entity("Invoice", invoice.getId())
                .scope(invoice.getOrganizationId(), invoice.getVesselId())
                .before(AuditJson.of("invoiceNumber", invoice.getInvoiceNumber(), "paymentId", payment.getId(),
                        "amount", payment.getAmount().toPlainString(), "receivedOn", payment.getReceivedOn(),
                        "method", payment.getMethod(), "reference", payment.getReference()))
                .build());
        return invoice.getServiceRequestId();
    }

    private Invoice inScope(Long invoiceId, AccessScope scope) {
        Invoice invoice = invoices.findById(invoiceId)
                .orElseThrow(() -> NotFoundException.ofResource("Invoice", invoiceId));
        if (!scope.permitsVessel(invoice.getVesselId())) {
            throw NotFoundException.ofResource("Invoice", invoiceId);
        }
        return invoice;
    }

    private static int checkedAdvance(Integer percent) {
        int value = percent == null ? 0 : percent;
        if (value < 0 || value > 100) {
            throw new ValidationException("The advance is a percentage from 0 to 100.");
        }
        return value;
    }

    private static void checkedDueDate(LocalDate due) {
        if (due != null && due.isBefore(BusinessTime.today().minusYears(1))) {
            throw new ValidationException("Choose a payment due date that is not more than a year in the past.");
        }
    }

    private static String trimmed(String value, int max, String what) {
        if (value == null || value.isBlank()) return null;
        String t = value.trim();
        if (t.length() > max) throw new ValidationException("Keep the " + what + " under " + max + " characters.");
        return t;
    }

    private String nextInvoiceNumber(String orgCode) {
        String prefix = "INV-" + orgCode + "-" + BusinessTime.today().format(MONTH) + "-";
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
