package com.seastella.servicerequest.internal;

import com.seastella.core.api.model.BaseEntity;
import com.seastella.core.api.model.VesselScoped;
import com.seastella.servicerequest.api.Priority;
import com.seastella.servicerequest.api.ResolutionType;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The Service Request aggregate.
 *
 * <p><b>There is no {@code setStatus}.</b> Status changes only through
 * {@link #applyTransition}, which the state machine calls after every guard has
 * passed. A public setter would make the transition table advisory.
 *
 * <p>{@code vesselId} and {@code organizationId} are denormalised from the
 * spare on purpose: it makes the scope predicate a single-table filter instead
 * of a join through {@code spare}, and scope enforcement should not depend on
 * anyone remembering to write that join.
 */
@Entity
@Table(name = "service_request")
public class ServiceRequest extends BaseEntity implements VesselScoped {

    @Column(name = "request_number", nullable = false, unique = true, length = 40)
    private String requestNumber;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Column(name = "spare_id", nullable = false)
    private Long spareId;

    @Column(name = "problem_type_id")
    private Long problemTypeId;

    @Column(name = "raised_by_user_id", nullable = false)
    private Long raisedByUserId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", nullable = false, length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private Priority priority = Priority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private ServiceRequestStatus status = ServiceRequestStatus.REPORTED;

    @Column(name = "operational_approved_by_user_id")
    private Long operationalApprovedByUserId;

    @Column(name = "operational_approved_at")
    private Instant operationalApprovedAt;

    @Column(name = "assigned_engineer_user_id")
    private Long assignedEngineerUserId;

    @Column(name = "assigned_by_user_id")
    private Long assignedByUserId;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_type", nullable = false, length = 24)
    private ResolutionType resolutionType = ResolutionType.NONE;

    @Column(name = "last_reason", length = 2000)
    private String lastReason;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by_user_id")
    private Long closedByUserId;

    @Column(name = "seed_marker", length = 8)
    private String seedMarker;

    protected ServiceRequest() {
    }

    public ServiceRequest(String requestNumber, Long organizationId, Long vesselId, Long spareId,
                          Long raisedByUserId, String title, String description, Priority priority) {
        this.requestNumber = requestNumber;
        this.organizationId = organizationId;
        this.vesselId = vesselId;
        this.spareId = spareId;
        this.raisedByUserId = raisedByUserId;
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.status = ServiceRequestStatus.REPORTED;
    }

    @Override
    public Long getVesselId() { return vesselId; }

    public String getRequestNumber() { return requestNumber; }
    public Long getOrganizationId() { return organizationId; }
    public Long getSpareId() { return spareId; }
    public Long getProblemTypeId() { return problemTypeId; }
    public Long getRaisedByUserId() { return raisedByUserId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Priority getPriority() { return priority; }
    public ServiceRequestStatus getStatus() { return status; }
    public Long getOperationalApprovedByUserId() { return operationalApprovedByUserId; }
    public Instant getOperationalApprovedAt() { return operationalApprovedAt; }
    public Long getAssignedEngineerUserId() { return assignedEngineerUserId; }
    public Long getAssignedByUserId() { return assignedByUserId; }
    public Instant getAssignedAt() { return assignedAt; }
    public ResolutionType getResolutionType() { return resolutionType; }
    public String getLastReason() { return lastReason; }
    public Instant getClosedAt() { return closedAt; }
    public Long getClosedByUserId() { return closedByUserId; }
    public String getSeedMarker() { return seedMarker; }

    public void setProblemTypeId(Long id) { this.problemTypeId = id; }
    public void setResolutionType(ResolutionType t) { this.resolutionType = t; }
    public void markSeed() { this.seedMarker = "SEED"; }

    /**
     * Package-private: only {@link ServiceRequestStateMachine} may move status,
     * and only after its guards have run.
     */
    void applyTransition(ServiceRequestStatus to, TransitionRequest request, Instant at) {
        this.status = to;
        this.lastReason = request.reason();

        switch (request.action()) {
            case APPROVE_OPERATIONAL -> {
                this.operationalApprovedByUserId = request.actorUserId();
                this.operationalApprovedAt = at;
            }
            case ASSIGN_ENGINEER -> {
                this.assignedEngineerUserId = request.engineerUserId();
                this.assignedByUserId = request.actorUserId();
                this.assignedAt = at;
            }
            case COMPLETE -> {
                this.closedAt = at;
                this.closedByUserId = request.actorUserId();
                this.resolutionType = ResolutionType.ENGINEER_VISIT;
            }
            case CLOSE_NO_COST -> {
                this.closedAt = at;
                this.closedByUserId = request.actorUserId();
                if (this.resolutionType == ResolutionType.NONE) {
                    this.resolutionType = ResolutionType.ASSISTANT;
                }
            }
            case REJECT -> {
                this.closedAt = at;
                this.closedByUserId = request.actorUserId();
            }
            default -> { /* no aggregate side effect */ }
        }
    }

    /** Seed support: lets fixtures place a request in a given state directly. */
    public void seedStatus(ServiceRequestStatus status) {
        this.status = status;
    }

    public void seedAssignment(Long engineerUserId, Long assignedByUserId, Instant at) {
        this.assignedEngineerUserId = engineerUserId;
        this.assignedByUserId = assignedByUserId;
        this.assignedAt = at;
    }

    public void seedApproval(Long approverUserId, Instant at) {
        this.operationalApprovedByUserId = approverUserId;
        this.operationalApprovedAt = at;
    }

    public void seedClosure(Long byUserId, Instant at, ResolutionType resolution) {
        this.closedByUserId = byUserId;
        this.closedAt = at;
        this.resolutionType = resolution;
    }
}
