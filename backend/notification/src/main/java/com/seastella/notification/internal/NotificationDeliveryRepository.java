package com.seastella.notification.internal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    /** Each sender takes only its own channel - an email sender must never pick up a WhatsApp row. */
    List<NotificationDelivery> findTop50ByChannelAndStatusOrderByIdAsc(String channel, String status);

    List<NotificationDelivery> findAllByOrderByIdDesc(Pageable page);

    @Modifying
    @Query("""
            update NotificationDelivery d set d.status = :skipped, d.lastError = :reason
            where d.status = :pending and d.channel = :channel
            """)
    int skipPending(@Param("channel") String channel, @Param("pending") String pending,
                    @Param("skipped") String skipped, @Param("reason") String reason);
}
