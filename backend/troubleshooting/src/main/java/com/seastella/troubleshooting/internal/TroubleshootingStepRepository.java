package com.seastella.troubleshooting.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TroubleshootingStepRepository extends JpaRepository<TroubleshootingStep, Long> {

    Optional<TroubleshootingStep> findByFlowIdAndStepKey(Long flowId, String stepKey);

    List<TroubleshootingStep> findByFlowIdOrderByDisplayOrderAsc(Long flowId);

    long countByFlowId(Long flowId);

    void deleteByFlowId(Long flowId);
}
