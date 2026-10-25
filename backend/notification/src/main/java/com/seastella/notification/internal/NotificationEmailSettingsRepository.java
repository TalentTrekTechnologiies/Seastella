package com.seastella.notification.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface NotificationEmailSettingsRepository extends JpaRepository<NotificationEmailSettings, Long> {

    @Query("select s.enabled from NotificationEmailSettings s where s.id = :id")
    Optional<Boolean> findEnabledById(@Param("id") Long id);

    @Query("select s.whatsappEnabled from NotificationEmailSettings s where s.id = :id")
    Optional<Boolean> findWhatsappEnabledById(@Param("id") Long id);

    /** Email is on unless an administrator paused it; a missing settings row must not stop alerts. */
    default boolean emailEnabled() {
        return findEnabledById(NotificationEmailSettings.SINGLETON_ID).orElse(true);
    }

    default boolean whatsappEnabled() {
        return findWhatsappEnabledById(NotificationEmailSettings.SINGLETON_ID).orElse(true);
    }
}
