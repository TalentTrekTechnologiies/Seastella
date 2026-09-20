package com.seastella.fleet.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Documents and certificates other modules may read (SoW s7).
 *
 * <p>Notification uses it to warn before a certificate expires, and reporting
 * to list what is expiring across a fleet. Neither reaches into fleet's tables,
 * and neither can read a file: this port carries the record, not the bytes.
 */
public interface DocumentDirectory {

    enum OwnerType { VESSEL, SPARE, SERVICE_REQUEST }

    enum DocumentType { CERTIFICATE, MANUAL, PHOTO, REPORT, OTHER }

    /** Current certificates expiring on or before a date, soonest first. */
    List<CertificateRef> certificatesExpiringBy(LocalDate date);

    /** Current certificates for vessels, soonest expiry first; for reports. */
    List<CertificateRef> certificatesForVessels(List<Long> vesselIds);

    record CertificateRef(Long id, Long organizationId, Long vesselId, String vesselName,
                          OwnerType ownerType, Long ownerId, String attachedTo, String title,
                          String certificateNumber, String issuingAuthority, LocalDate issuedDate,
                          LocalDate expiryDate) {}
}
