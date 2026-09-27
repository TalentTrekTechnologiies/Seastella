package com.seastella.invoice.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface InvoicePaymentRepository extends JpaRepository<InvoicePayment, Long> {

    List<InvoicePayment> findByInvoiceIdOrderByReceivedOnAscIdAsc(Long invoiceId);

    List<InvoicePayment> findByInvoiceIdInOrderByReceivedOnAscIdAsc(Collection<Long> invoiceIds);

    @Query("select coalesce(sum(p.amount), 0) from InvoicePayment p where p.invoiceId = :invoiceId")
    BigDecimal totalReceived(@Param("invoiceId") Long invoiceId);
}
