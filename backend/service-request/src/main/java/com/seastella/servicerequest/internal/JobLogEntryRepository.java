package com.seastella.servicerequest.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobLogEntryRepository extends JpaRepository<JobLogEntry, Long> {

    List<JobLogEntry> findByServiceRequestIdOrderByOccurredAtAscIdAsc(Long serviceRequestId);

    boolean existsByServiceRequestIdAndKind(Long serviceRequestId, JobLogEntry.Kind kind);
}
