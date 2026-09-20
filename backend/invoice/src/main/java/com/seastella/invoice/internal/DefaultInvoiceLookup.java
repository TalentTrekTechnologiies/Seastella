package com.seastella.invoice.internal;

import com.seastella.identity.api.UserDirectory;
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
    private final UserDirectory users;

    DefaultInvoiceLookup(InvoiceRepository invoices, UserDirectory users) {
        this.invoices = invoices;
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceView> forRequest(Long serviceRequestId) {
        List<Invoice> rows = invoices.findByServiceRequestIdOrderByCreatedAtDesc(serviceRequestId);
        Map<Long, UserDirectory.UserRef> people = users.findAll(rows.stream()
                .flatMap(i -> Stream.of(i.getRaisedByUserId(), i.getDecidedByUserId()))
                .filter(Objects::nonNull)
                .toList());

        return rows.stream().map(i -> new InvoiceView(
                i.getId(), i.getInvoiceNumber(), i.getAmount().toPlainString(), i.getCurrency(),
                i.getDescription(), i.getStatus().name(), i.getStatus().label(),
                i.getRaisedByUserId(), nameOf(people, i.getRaisedByUserId()), i.getCreatedAt(),
                i.getDecidedByUserId(), nameOf(people, i.getDecidedByUserId()), i.getDecidedAt(),
                i.getDecisionNote()))
                .toList();
    }

    private static String nameOf(Map<Long, UserDirectory.UserRef> people, Long id) {
        UserDirectory.UserRef ref = id == null ? null : people.get(id);
        return ref == null ? null : ref.fullName();
    }
}
