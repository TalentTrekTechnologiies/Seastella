package com.seastella.servicerequest.internal;

import com.seastella.core.api.model.BaseEntity;
import com.seastella.core.api.model.VesselScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The engineer's completion report (SoW s6.3).
 *
 * <p>Visible to the Service Coordinator. <b>Not</b> surfaced to the Ship
 * Manager directly - they receive the Coordinator's relayed summary instead.
 * That restriction lives in the read model, not in a hidden UI field.
 *
 * <p>{@code finalCost} is what the Coordinator reconciles against the accepted
 * invoice; any variance is flagged rather than silently absorbed.
 */
@Entity
@Table(name = "completion_report")
public class CompletionReport extends BaseEntity implements VesselScoped {

    @Column(name = "service_request_id", nullable = false, unique = true)
    private Long serviceRequestId;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Column(name = "engineer_user_id", nullable = false)
    private Long engineerUserId;

    @Column(name = "work_performed", nullable = false, length = 4000)
    private String workPerformed;

    @Column(name = "parts_used", length = 2000)
    private String partsUsed;

    @Column(name = "outcome", nullable = false, length = 400)
    private String outcome;

    @Column(name = "final_cost", precision = 14, scale = 2)
    private BigDecimal finalCost;

    @Column(name = "service_date")
    private LocalDate serviceDate;

    @Column(name = "running_hours_at_service", precision = 12, scale = 2)
    private BigDecimal runningHoursAtService;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Column(name = "cost_variance", precision = 14, scale = 2)
    private BigDecimal costVariance;

    @Column(name = "relay_note", length = 2000)
    private String relayNote;

    @Column(name = "seed_marker", length = 8)
    private String seedMarker;

    protected CompletionReport() {
    }

    public CompletionReport(Long serviceRequestId, Long vesselId, Long engineerUserId,
                            String workPerformed, String outcome, LocalDate serviceDate) {
        this.serviceRequestId = serviceRequestId;
        this.vesselId = vesselId;
        this.engineerUserId = engineerUserId;
        this.workPerformed = workPerformed;
        this.outcome = outcome;
        this.serviceDate = serviceDate;
        this.reportedAt = Instant.now();
    }

    @Override public Long getVesselId() { return vesselId; }
    public Long getServiceRequestId() { return serviceRequestId; }
    public Long getEngineerUserId() { return engineerUserId; }
    public String getWorkPerformed() { return workPerformed; }
    public String getPartsUsed() { return partsUsed; }
    public String getOutcome() { return outcome; }
    public BigDecimal getFinalCost() { return finalCost; }
    public LocalDate getServiceDate() { return serviceDate; }
    public BigDecimal getRunningHoursAtService() { return runningHoursAtService; }
    public Instant getReportedAt() { return reportedAt; }
    public Instant getReconciledAt() { return reconciledAt; }
    public BigDecimal getCostVariance() { return costVariance; }
    public String getRelayNote() { return relayNote; }

    public void setPartsUsed(String v) { this.partsUsed = v; }
    public void setFinalCost(BigDecimal v) { this.finalCost = v; }
    public void setRunningHoursAtService(BigDecimal v) { this.runningHoursAtService = v; }
    public void setReportedAt(Instant t) { this.reportedAt = t; }
    public void markSeed() { this.seedMarker = "SEED"; }

    /** Coordinator reconciliation (SoW s6.3 step 2). */
    public void reconcile(BigDecimal acceptedInvoiceAmount, String relayNote, Instant at) {
        this.reconciledAt = at;
        this.relayNote = relayNote;
        if (finalCost != null && acceptedInvoiceAmount != null) {
            this.costVariance = finalCost.subtract(acceptedInvoiceAmount);
        }
    }

    /** True when the final cost differs from what the Ship Manager accepted. */
    public boolean hasCostVariance() {
        return costVariance != null && costVariance.signum() != 0;
    }
}
