package com.seastella.troubleshooting.internal;

import com.seastella.core.api.model.BaseEntity;
import com.seastella.core.api.model.VesselScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The conversation on one service request (SoW s6.1, CHT-04, CHT-10).
 *
 * <p>One thread per request, from the first guided check to the last word of
 * the live chat. It opens in {@code ASSISTANT} when the checks start, becomes
 * {@code LIVE} if the Captain escalates, and is {@code CLOSED} when the request
 * moves on. Nothing is deleted when it closes: the transcript is part of the
 * service record.
 */
@Entity
@Table(name = "conversation")
public class Conversation extends BaseEntity implements VesselScoped {

    static final String ASSISTANT = "ASSISTANT";
    static final String LIVE = "LIVE";
    static final String CLOSED = "CLOSED";

    @Column(name = "service_request_id", nullable = false)
    private Long serviceRequestId;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    /** Null until a live agent is asked for; some requests never need one. */
    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected Conversation() {
    }

    Conversation(Long serviceRequestId, Long vesselId, String status, Instant openedAt) {
        this.serviceRequestId = serviceRequestId;
        this.vesselId = vesselId;
        this.status = status;
        this.openedAt = openedAt;
        if (LIVE.equals(status)) this.escalatedAt = openedAt;
    }

    @Override
    public Long getVesselId() { return vesselId; }

    Long getServiceRequestId() { return serviceRequestId; }
    String getStatus() { return status; }
    boolean isLive() { return LIVE.equals(status); }
    boolean isClosed() { return CLOSED.equals(status); }
    Instant getOpenedAt() { return openedAt; }
    Instant getEscalatedAt() { return escalatedAt; }
    Instant getClosedAt() { return closedAt; }

    /** A live agent was asked for. Re-escalation keeps the first time. */
    void escalate(Instant at) {
        this.status = LIVE;
        if (this.escalatedAt == null) this.escalatedAt = at;
    }

    void close(Instant at) {
        this.status = CLOSED;
        this.closedAt = at;
    }
}
