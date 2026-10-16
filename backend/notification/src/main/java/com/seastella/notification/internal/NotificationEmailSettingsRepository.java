package com.seastella.notification.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface NotificationEmailSettingsRepository extends JpaRepository<NotificationEmailSettings, Long> {

    @Query("select s.enabled from NotificationEmailSettings s where s.id = :id")
    Optional<Boolean> findEnabledById(@Param("id") Long id);
}
