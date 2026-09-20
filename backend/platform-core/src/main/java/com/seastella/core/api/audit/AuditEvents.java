package com.seastella.core.api.audit;

import com.seastella.core.api.event.DomainEvent;

import java.time.Instant;

/** Events the audit trail itself publishes. */
public final class AuditEvents {

    private AuditEvents() {
    }

    /**
     * An entry has been written (FEE-04).
     *
     * <p>Carries the id and nothing else on purpose: the activity feed already
     * knows how to read an entry and how to say it in words, and a second copy
     * of that shape travelling on the bus is a second thing to keep in step.
     * Consumers listen after commit, so an entry announced here is one that is
     * really in the trail.
     */
    public record Recorded(Long entryId, String action, Long organizationId, Long vesselId,
                           Instant occurredAt) implements DomainEvent {

        @Override
        public String eventType() {
            return "AUDIT_ENTRY_RECORDED";
        }

        @Override
        public String summary() {
            return action;
        }
    }
}
