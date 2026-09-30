package com.seastella.notification.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

interface EquipmentExpiryAlertStateRepository extends JpaRepository<EquipmentExpiryAlertState, Long> {

    /** The bookmarks for a sweep's units, in one read rather than one per unit. */
    List<EquipmentExpiryAlertState> findBySpareIdIn(Collection<Long> spareIds);
}
