package com.seastella.invoice.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Money received against an invoice, as recorded by the Coordinator. A record
 * of Seastella's own finance process, not a transaction the platform made.
 */
@Entity
@Table(name = "invoice_payment")
public class InvoicePayment extends BaseEntity {

    /** How the money arrived. */
    public enum Method { BANK_TRANSFER, CHEQUE, CASH, CARD, OTHER }

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "received_on", nullable = false)
    private LocalDate receivedOn;

    @Column(name = "method", nullable = false, length = 24)
    private String method;

    @Column(name = "reference", length = 120)
    private String reference;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "recorded_by_user_id", nullable = false)
    private Long recordedByUserId;

    protected InvoicePayment() {
    }

    public InvoicePayment(Long invoiceId, BigDecimal amount, LocalDate receivedOn, Method method,
                          String reference, String note, Long recordedByUserId) {
        this.invoiceId = invoiceId;
        this.amount = amount;
        this.receivedOn = receivedOn;
        this.method = method.name();
        this.reference = reference;
        this.note = note;
        this.recordedByUserId = recordedByUserId;
    }

    public Long getInvoiceId() { return invoiceId; }
    public BigDecimal getAmount() { return amount; }
    public LocalDate getReceivedOn() { return receivedOn; }
    public String getMethod() { return method; }
    public String getReference() { return reference; }
    public String getNote() { return note; }
    public Long getRecordedByUserId() { return recordedByUserId; }
}
