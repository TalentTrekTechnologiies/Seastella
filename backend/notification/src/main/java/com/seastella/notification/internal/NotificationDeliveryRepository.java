package com.seastella.notification.internal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    List<NotificationDelivery> findTop50ByStatusOrderByIdAsc(String status);

    List<NotificationDelivery> findAllByOrderByIdDesc(Pageable page);
}
