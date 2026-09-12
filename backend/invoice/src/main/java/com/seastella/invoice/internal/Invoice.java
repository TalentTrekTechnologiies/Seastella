package com.seastella.invoice.internal;

import com.seastella.core.api.model.BaseEntity;
import com.seastella.core.api.model.VesselScoped;
import com.seastella.invoice.api.InvoiceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An invoice raised against a service request (SoW s6.2).
 *
 * <p>An acceptance-and-tracking record only. No payment reference, no gateway
 * identifier, no settled amount - their absence is a decision, recorded in
 * docs/03-data-model.md, not an omission.
 */
@Entity
@Table(name = "invoice")
public class Invoice extends BaseEntity implements VesselScoped {

    @Column(name = "invoice_number", nullable = false, unique = true, length = 40)
    private String invoiceNumber;

    @Column(name = "service_request_id", nullable = false)
    private Long serviceRequestId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Column(name = "raised_by_user_id", nullable = false)
    private Long raisedByUserId;

    /** ISO-4217. Default is an OI-06 working assumption. */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "description", nullable = false, length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private InvoiceStatus status = InvoiceStatus.RAISED;

    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = 2000)
    private String decisionNote;

    @Column(name = "supersedes_invoice_id")
    private Long supersedesInvoiceId;

    @Column(name = "seed_marker", length = 8)
    private String seedMarker;

    protected Invoice() {
    }

    public Invoice(String invoiceNumber, Long serviceRequestId, Long organizationId, Long vesselId,
                   Long raisedByUserId, BigDecimal amount, String currency, String description) {
        this.invoiceNumber = invoiceNumber;
        this.serviceRequestId = serviceRequestId;
        this.organizationId = organizationId;
        this.vesselId = vesselId;
        this.raisedByUserId = raisedByUserId;
        this.amount = amount;
        this.currency = currency == null ? "USD" : currency;
        this.description = description;
        this.status = InvoiceStatus.RAISED;
    }

    @Override public Long getVesselId() { return vesselId; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public Long getServiceRequestId() { return serviceRequestId; }
    public Long getOrganizationId() { return organizationId; }
    public Long getRaisedByUserId() { return raisedByUserId; }
    public String getCurrency() { return currency; }
    public BigDecimal getAmount() { return amount; }
    public String getDescription() { return description; }
    public InvoiceStatus getStatus() { return status; }
    public Long getDecidedByUserId() { return decidedByUserId; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
    public Long getSupersedesInvoiceId() { return supersedesInvoiceId; }
    public String getSeedMarker() { return seedMarker; }

    public void setSupersedesInvoiceId(Long id) { this.supersedesInvoiceId = id; }
    public void markSeed() { this.seedMarker = "SEED"; }

    public void accept(Long shipManagerUserId, String note, Instant at) {
        transition(InvoiceStatus.ACCEPTED, shipManagerUserId, note, at);
    }

    public void reject(Long shipManagerUserId, String note, Instant at) {
        transition(InvoiceStatus.REJECTED, shipManagerUserId, note, at);
    }

    public void query(Long shipManagerUserId, String note, Instant at) {
        transition(InvoiceStatus.QUERIED, shipManagerUserId, note, at);
    }

    public void supersede() {
        this.status = InvoiceStatus.SUPERSEDED;
    }

    /** Seed support only. */
    public void seedDecision(InvoiceStatus status, Long byUserId, String note, Instant at) {
        this.status = status;
        this.decidedByUserId = byUserId;
        this.decisionNote = note;
        this.decidedAt = at;
    }

    private void transition(InvoiceStatus to, Long userId, String note, Instant at) {
        if (this.status != InvoiceStatus.RAISED) {
            throw new IllegalStateException(
                    "Invoice " + invoiceNumber + " is " + status.label() + " and cannot be decided again");
        }
        this.status = to;
        this.decidedByUserId = userId;
        this.decisionNote = note;
        this.decidedAt = at;
    }
}
