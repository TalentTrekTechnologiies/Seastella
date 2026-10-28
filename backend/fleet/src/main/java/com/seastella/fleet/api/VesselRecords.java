package com.seastella.fleet.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * A vessel's particulars and equipment register, and the filing of documents
 * the platform itself produces - the SBMC certificate and the QM-F11
 * pre-survey form.
 *
 * <p>Reporting assembles those documents because it is the module that can
 * also see maintenance status and open requests; fleet keeps the vessel's
 * records and its documents. Every call checks the caller's vessel scope.
 * Who may <em>generate</em> a document is the caller's decision, made before
 * it gets here; filing only needs the vessel to be in scope.
 */
public interface VesselRecords {

    Optional<VesselParticulars> particulars(Long vesselId);

    /** Every item on the vessel in VMP order, with what surveys ask about it. */
    List<EquipmentRecord> register(Long vesselId);

    /** Stores a generated file as one of the vessel's documents; returns its id. */
    Long file(GeneratedFile file);

    /** Takes a filed document out of the current set - a revoked certificate stops warning of its expiry. */
    void withdraw(Long documentId, String reason);

    /** The bytes of a filed document, exactly as filed. */
    byte[] read(Long documentId);

    record VesselParticulars(Long id, Long organizationId, String organizationName, String name, String imoNumber,
                             String mmsi, String callSign, String flag, String vesselClass, String area,
                             String vesselType, BigDecimal dwt, String status, Instant createdAt,
                             Instant updatedAt, String updatedBy) {}

    /**
     * @param lastReplacement       the newest history entry recorded as a replacement
     * @param lastSphereReplacement the newest gyro sphere replacement
     */
    record EquipmentRecord(Long spareId, String path, String name, String make, String model, String serialNumber,
                           LocalDate expirationDate, LocalDate lastAnnualServiceDate, BigDecimal runningHours,
                           LocalDate lastReplacement, LocalDate lastSphereReplacement) {}

    /**
     * @param supersedesDocumentId the document this one replaces (an earlier SBMC), or null
     */
    record GeneratedFile(Long vesselId, DocumentDirectory.DocumentType type, String title, String certificateNumber,
                         String issuingAuthority, LocalDate issuedDate, LocalDate expiryDate, String fileName,
                         byte[] content, Long supersedesDocumentId) {}
}
