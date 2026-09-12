package com.seastella.core.api.event;

import java.time.Instant;

/**
 * Marker for anything a module publishes when its state changes.
 *
 * <p>Events are the only side-effect channel across module boundaries
 * (docs/02 s2.1 rule 3). {@code activity-feed}, {@code notification} and the
 * audit log all consume them, which is why a state change must publish exactly
 * one event describing what happened rather than calling three collaborators.
 */
public interface DomainEvent {

    /** Stable event name, e.g. {@code SERVICE_REQUEST_TRANSITIONED}. */
    String eventType();

    Instant occurredAt();

    /** Organization this event belongs to; null for platform-level events. */
    Long organizationId();

    /** Vessel this event belongs to; null when not vessel-scoped. */
    Long vesselId();

    /** Short human-readable line for the activity feed (SoW s8.5). */
    String summary();
}
