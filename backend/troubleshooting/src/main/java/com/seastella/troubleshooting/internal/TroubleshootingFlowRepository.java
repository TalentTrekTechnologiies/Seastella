package com.seastella.troubleshooting.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TroubleshootingFlowRepository extends JpaRepository<TroubleshootingFlow, Long> {

    /**
     * Published flows that could apply, most specific first: this category and
     * problem type, then this category for any problem, then the general
     * fallback. Within each, the newest version first.
     */
    default List<TroubleshootingFlow> candidates(Long categoryId, Long problemTypeId) {
        return candidates(categoryId, problemTypeId, TroubleshootingFlow.Status.PUBLISHED);
    }

    @Query("""
            select f from TroubleshootingFlow f
            where f.status = :status
              and ((f.equipmentCategoryId = :categoryId and f.problemTypeId = :problemTypeId)
                or (f.equipmentCategoryId = :categoryId and f.problemTypeId is null)
                or (f.equipmentCategoryId is null and f.problemTypeId is null))
            order by case
                       when f.problemTypeId is not null then 0
                       when f.equipmentCategoryId is not null then 1
                       else 2
                     end,
                     f.flowVersion desc
            """)
    List<TroubleshootingFlow> candidates(@Param("categoryId") Long categoryId,
                                         @Param("problemTypeId") Long problemTypeId,
                                         @Param("status") TroubleshootingFlow.Status status);

    boolean existsByCode(String code);

    List<TroubleshootingFlow> findByCodeOrderByFlowVersionDesc(String code);

    Optional<TroubleshootingFlow> findByCodeAndStatus(String code, TroubleshootingFlow.Status status);

    List<TroubleshootingFlow> findByStatus(TroubleshootingFlow.Status status);

    List<TroubleshootingFlow> findAllByOrderByCodeAscFlowVersionDesc();
}
