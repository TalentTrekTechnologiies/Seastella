package com.seastella.fleet.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EquipmentCategoryRepository extends JpaRepository<EquipmentCategory, Long> {

    Optional<EquipmentCategory> findByCode(String code);

    List<EquipmentCategory> findAllByOrderByDisplayOrderAsc();
}
