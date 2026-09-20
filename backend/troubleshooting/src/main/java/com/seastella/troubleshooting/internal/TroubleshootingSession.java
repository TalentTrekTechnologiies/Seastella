package com.seastella.troubleshooting.internal;

import com.seastella.core.api.model.BaseEntity;
import com.seastella.core.api.model.VesselScoped;
import com.seastella.troubleshooting.api.TroubleshootingOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One run of the guided checks on a request.
 *
 * <pre>
 * IN_PROGRESS ──answer ends in an outcome──▶ OUTCOME_REACHED ──notes saved──▶ COMPLETED
 * </pre>
 */
@Entity
@Table(name = "troubleshooting_session")
public class TroubleshootingSession extends BaseEntity implements VesselScoped {

    enum Status { IN_PROGRESS, OUTCOME_REACHED, COMPLETED }

    @Column(name = "service_request_id", nullable = false)
    private Long serviceRequestId;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Column(name = "flow_id", nullable = false)
    private Long flowId;

    @Column(name = "flow_version", nullable = false)
    private int flowVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "current_step_id")
    private Long currentStepId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 16)
    private TroubleshootingOutcome outcome;

    @Column(name = "root_cause_note", length = 1000)
    private String rootCauseNote;

    @Column(name = "temporary_fix_note", length = 1000)
    private String temporaryFixNote;

    @Column(name = "started_by_user_id", nullable = false)
    private Long startedByUserId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected TroubleshootingSession() {
    }

    TroubleshootingSession(Long serviceRequestId, Long vesselId, TroubleshootingFlow flow,
                           Long firstStepId, Long startedByUserId, Instant startedAt) {
        this.serviceRequestId = serviceRequestId;
        this.vesselId = vesselId;
        this.flowId = flow.getId();
        this.flowVersion = flow.getFlowVersion();
        this.status = Status.IN_PROGRESS;
        this.currentStepId = firstStepId;
        this.startedByUserId = startedByUserId;
        this.startedAt = startedAt;
    }

    @Override
    public Long getVesselId() { return vesselId; }

    Long getServiceRequestId() { return serviceRequestId; }
    Long getFlowId() { return flowId; }
    int getFlowVersion() { return flowVersion; }
    Status getStatus() { return status; }
    Long getCurrentStepId() { return currentStepId; }
    TroubleshootingOutcome getOutcome() { return outcome; }
    String getRootCauseNote() { return rootCauseNote; }
    String getTemporaryFixNote() { return temporaryFixNote; }
    Long getStartedByUserId() { return startedByUserId; }
    Instant getStartedAt() { return startedAt; }
    Instant getCompletedAt() { return completedAt; }

    void moveTo(Long stepId) {
        this.currentStepId = stepId;
    }

    void reach(TroubleshootingOutcome outcome) {
        this.outcome = outcome;
        this.currentStepId = null;
        this.status = Status.OUTCOME_REACHED;
    }

    void complete(String rootCauseNote, String temporaryFixNote, Instant at) {
        this.rootCauseNote = rootCauseNote;
        this.temporaryFixNote = temporaryFixNote;
        this.completedAt = at;
        this.status = Status.COMPLETED;
    }
}
