package com.seastella.notification.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface CertificateAlertStateRepository extends JpaRepository<CertificateAlertState, Long> {

    Optional<CertificateAlertState> findByDocumentId(Long documentId);
}
