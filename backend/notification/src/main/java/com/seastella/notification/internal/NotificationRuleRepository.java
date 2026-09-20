package com.seastella.notification.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRuleRepository extends JpaRepository<NotificationRule, Long> {

    List<NotificationRule> findByEventTypeAndActiveTrue(String eventType);

    List<NotificationRule> findAllByOrderByEventTypeAscRecipientRoleAsc();
}
