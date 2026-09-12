package com.seastella.core.internal.event;

import com.seastella.core.api.event.DomainEvent;
import com.seastella.core.api.event.DomainEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * In-process implementation, backed by Spring's application event publisher.
 *
 * <p>Consumers annotated {@code @TransactionalEventListener(AFTER_COMMIT)} do
 * not observe an event whose transaction rolled back - which is what keeps the
 * activity feed from announcing an approval that never persisted.
 */
@Component
class SpringDomainEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher delegate;

    SpringDomainEventPublisher(ApplicationEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(DomainEvent event) {
        delegate.publishEvent(event);
    }
}
