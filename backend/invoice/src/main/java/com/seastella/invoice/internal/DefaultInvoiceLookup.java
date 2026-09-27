package com.seastella.invoice.internal;

import com.seastella.identity.api.UserDirectory;
import com.seastella.invoice.api.PaymentPosition;
import com.seastella.servicerequest.api.InvoiceLookup;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

@Component
class DefaultInvoiceLookup implements InvoiceLookup {

    private final InvoiceRepository invoices;
    private final InvoicePaymentRepository payments;
    private final UserDirectory users;

    DefaultInvoiceLookup(InvoiceRepository invoices, InvoicePaymentRepository payments, UserDirectory users) {
        this.invoices = invoices;
        this.payments = payments;
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceView> forRequest(Long serviceRequestId) {
        List<Invoice> rows = invoices.findByServiceRequestIdOrderByCreatedAtDesc(serviceRequestId);
        Map<Long, List<InvoicePayment>> paid = payments.findByInvoiceIdInOrderByReceivedOnAscIdAsc(
                        rows.stream().map(Invoice::getId).toList()).stream()
                .collect(java.util.stream.Collectors.groupingBy(InvoicePayment::getInvoiceId));
        Map<Long, UserDirectory.UserRef> people = users.findAll(Stream.concat(
                        rows.stream().flatMap(i -> Stream.of(i.getRaisedByUserId(), i.getDecidedByUserId())),
                        paid.values().stream().flatMap(List::stream).map(InvoicePayment::getRecordedByUserId))
                .filter(Objects::nonNull)
                .toList());
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);

        return rows.stream().map(i -> new InvoiceView(
                i.getId(), i.getInvoiceNumber(), i.getAmount().toPlainString(), i.getCurrency(),
                i.getDescription(), i.getStatus().name(), i.getStatus().label(),
                i.getRaisedByUserId(), nameOf(people, i.getRaisedByUserId()), i.getCreatedAt(),
                i.getDecidedByUserId(), nameOf(people, i.getDecidedByUserId()), i.getDecidedAt(),
                i.getDecisionNote(), payment(i, paid.getOrDefault(i.getId(), List.of()), people, today)))
                .toList();
    }

    private static InvoiceLookup.Payment payment(Invoice i, List<InvoicePayment> lines,
                                                 Map<Long, UserDirectory.UserRef> people, java.time.LocalDate today) {
        java.math.BigDecimal received = lines.stream().map(InvoicePayment::getAmount)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        PaymentPosition p = PaymentPosition.of(i.getStatus(), i.getAmount(), i.getAdvancePercent(),
                i.getPaymentDueDate(), received, today);
        return new InvoiceLookup.Payment(p.advancePercent(), p.advanceAmount().toPlainString(), p.dueDate(),
                p.received().toPlainString(), p.balance().toPlainString(), p.advanceReceived(), p.status(),
                p.overdue(), lines.stream().map(l -> new InvoiceLookup.PaymentLine(l.getId(),
                        l.getAmount().toPlainString(), l.getReceivedOn(), l.getMethod(), l.getReference(),
                        l.getNote(), nameOf(people, l.getRecordedByUserId()), l.getCreatedAt())).toList());
    }

    private static String nameOf(Map<Long, UserDirectory.UserRef> people, Long id) {
        UserDirectory.UserRef ref = id == null ? null : people.get(id);
        return ref == null ? null : ref.fullName();
    }
}
