package com.seastella.troubleshooting.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TroubleshootingSessionRepository extends JpaRepository<TroubleshootingSession, Long> {

    Optional<TroubleshootingSession> findByServiceRequestId(Long serviceRequestId);

    long countByFlowId(Long flowId);

    /** Checks still being answered or awaiting findings on this version. */
    long countByFlowIdAndStatusNot(Long flowId, TroubleshootingSession.Status status);
}
