package com.seastella.reporting.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface VesselCertificateRepository extends JpaRepository<VesselCertificate, Long> {

    List<VesselCertificate> findByVesselIdOrderByIdDesc(Long vesselId);

    Optional<VesselCertificate> findFirstByVesselIdAndStatusOrderByIdDesc(Long vesselId, String status);
}
