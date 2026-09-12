package com.seastella.servicerequest.internal;

import com.seastella.core.api.model.VesselScoped;
import com.seastella.servicerequest.api.ServiceRequestAction;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Append-only record of every applied transition (SRQ-23).
 *
 * <p>This is what the Captain's "approval history" and the request timeline
 * render from, so it stores the actor's role as it was at the time - a later
 * role change must not rewrite what happened.
 */
@Entity
@Table(name = "service_request_transition")
public class ServiceRequestTransitionLog implements VesselScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_request_id", nullable = false)
    private Long serviceRequestId;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 40)
    private ServiceRequestStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 40)
    private ServiceRequestStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 40)
    private ServiceRequestAction action;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_role", length = 32)
    private String actorRole;

    @Column(name = "reason", length = 2000)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected ServiceRequestTransitionLog() {
    }

    ServiceRequestTransitionLog(Long serviceRequestId, Long vesselId,
                                ServiceRequestStatus fromStatus, ServiceRequestStatus toStatus,
                                ServiceRequestAction action, Long actorUserId, String actorRole,
                                String reason, Instant occurredAt) {
        this.serviceRequestId = serviceRequestId;
        this.vesselId = vesselId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.action = action;
        this.actorUserId = actorUserId;
        this.actorRole = actorRole;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public Long getServiceRequestId() { return serviceRequestId; }
    @Override public Long getVesselId() { return vesselId; }
    public ServiceRequestStatus getFromStatus() { return fromStatus; }
    public ServiceRequestStatus getToStatus() { return toStatus; }
    public ServiceRequestAction getAction() { return action; }
    public Long getActorUserId() { return actorUserId; }
    public String getActorRole() { return actorRole; }
    public String getReason() { return reason; }
    public Instant getOccurredAt() { return occurredAt; }
}
