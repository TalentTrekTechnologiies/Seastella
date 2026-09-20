package com.seastella.troubleshooting.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TroubleshootingResponseRepository extends JpaRepository<TroubleshootingResponse, Long> {

    List<TroubleshootingResponse> findBySessionIdOrderByIdAsc(Long sessionId);

    long countBySessionId(Long sessionId);
}
