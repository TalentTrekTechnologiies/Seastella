package com.seastella.servicerequest.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProblemTypeRepository extends JpaRepository<ProblemType, Long> {

    List<ProblemType> findByEquipmentCategoryIdOrderByDisplayOrderAsc(Long equipmentCategoryId);

    Optional<ProblemType> findByCode(String code);
}
