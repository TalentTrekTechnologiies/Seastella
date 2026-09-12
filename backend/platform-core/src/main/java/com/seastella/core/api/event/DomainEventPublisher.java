package com.seastella.core.api.event;

/**
 * Publishes domain events.
 *
 * <p>Deliberately an interface over Spring's publisher rather than the
 * publisher itself: swapping the in-process bus for a broker when a module is
 * extracted then changes one implementation class, and no publisher or consumer
 * (docs/02 s2.1).
 */
public interface DomainEventPublisher {

    void publish(DomainEvent event);
}
