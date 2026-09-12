package com.seastella.servicerequest.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface CompletionReportRepository extends JpaRepository<CompletionReport, Long> {

    Optional<CompletionReport> findByServiceRequestId(Long serviceRequestId);

    List<CompletionReport> findByEngineerUserIdOrderByReportedAtDesc(Long engineerUserId);

    List<CompletionReport> findByVesselIdIn(Set<Long> vesselIds);
}
