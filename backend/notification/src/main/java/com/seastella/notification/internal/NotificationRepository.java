package com.seastella.notification.internal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByRecipientUserIdAndInAppTrueOrderByCreatedAtDescIdDesc(Long recipientUserId, Pageable page);

    long countByRecipientUserIdAndInAppTrueAndReadAtIsNull(Long recipientUserId);

    Optional<Notification> findByIdAndRecipientUserId(Long id, Long recipientUserId);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.recipientUserId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);
}
