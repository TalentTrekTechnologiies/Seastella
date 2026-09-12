package com.seastella.servicerequest.api;

import com.seastella.core.api.event.DomainEvent;

import java.time.Instant;

/** Domain events published by the service-request module. */
public final class ServiceRequestEvents {

    private ServiceRequestEvents() {}

    /**
     * Published on every applied transition. The activity feed, the notification
     * fan-out and the audit log all consume this one event rather than each
     * being called separately.
     */
    public record Transitioned(
            Long serviceRequestId,
            String requestNumber,
            ServiceRequestStatus fromStatus,
            ServiceRequestStatus toStatus,
            ServiceRequestAction action,
            Long actorUserId,
            String actorRole,
            String reason,
            Long organizationId,
            Long vesselId,
            Long spareId,
            Instant occurredAt) implements DomainEvent {

        @Override
        public String eventType() { return "SERVICE_REQUEST_TRANSITIONED"; }

        @Override
        public String summary() {
            return requestNumber + ": " + action.label()
                    + " (" + fromStatus.label() + " to " + toStatus.label() + ")";
        }
    }
}
