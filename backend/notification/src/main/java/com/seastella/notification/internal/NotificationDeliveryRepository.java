package com.seastella.notification.internal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    List<NotificationDelivery> findTop50ByStatusOrderByIdAsc(String status);

    List<NotificationDelivery> findAllByOrderByIdDesc(Pageable page);

    @Modifying
    @Query("update NotificationDelivery d set d.status = :skipped, d.lastError = :reason where d.status = :pending")
    int skipPending(@Param("pending") String pending, @Param("skipped") String skipped,
                    @Param("reason") String reason);
}
