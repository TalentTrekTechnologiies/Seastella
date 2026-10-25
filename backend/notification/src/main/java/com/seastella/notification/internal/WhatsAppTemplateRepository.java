package com.seastella.notification.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WhatsAppTemplateRepository extends JpaRepository<WhatsAppTemplate, Long> {

    Optional<WhatsAppTemplate> findByEventType(String eventType);
}
