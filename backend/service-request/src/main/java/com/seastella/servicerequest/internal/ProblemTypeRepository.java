package com.seastella.servicerequest.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProblemTypeRepository extends JpaRepository<ProblemType, Long> {

    List<ProblemType> findByEquipmentCategoryIdOrderByDisplayOrderAsc(Long equipmentCategoryId);

    List<ProblemType> findByEquipmentCategoryIdAndActiveTrueOrderByDisplayOrderAsc(Long equipmentCategoryId);

    List<ProblemType> findAllByOrderByEquipmentCategoryIdAscDisplayOrderAsc();

    Optional<ProblemType> findByCode(String code);

    boolean existsByCode(String code);

    /** Requests raised against each problem type, for "in use" counts. */
    @Query("select r.problemTypeId, count(r) from ServiceRequest r where r.problemTypeId is not null group by r.problemTypeId")
    List<Object[]> requestCounts();
}
