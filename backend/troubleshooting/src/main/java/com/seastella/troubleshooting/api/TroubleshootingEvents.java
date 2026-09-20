package com.seastella.troubleshooting.api;

import com.seastella.core.api.event.DomainEvent;

import java.time.Instant;

/** Domain events published by the troubleshooting module. */
public final class TroubleshootingEvents {

    private TroubleshootingEvents() {}

    /** The Captain finished the guided checks on a request (SoW s11: notifies the Coordinator). */
    public record Completed(
            Long serviceRequestId,
            String requestNumber,
            TroubleshootingOutcome outcome,
            Long actorUserId,
            Long organizationId,
            Long vesselId,
            Instant occurredAt) implements DomainEvent {

        @Override
        public String eventType() { return "TROUBLESHOOTING_COMPLETED"; }

        @Override
        public String summary() {
            return requestNumber + ": guided checks finished - " + outcome.label().toLowerCase();
        }
    }
}
