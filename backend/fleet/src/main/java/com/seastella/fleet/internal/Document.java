package com.seastella.fleet.internal;

import com.seastella.core.api.model.BaseEntity;
import com.seastella.core.api.model.VesselScoped;
import com.seastella.fleet.api.DocumentDirectory.DocumentType;
import com.seastella.fleet.api.DocumentDirectory.OwnerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A file attached to a vessel, a spare or a request - a certificate, a manual,
 * a photograph (SoW s7).
 *
 * <p>Replacing a document does not overwrite it: the old row stays and points
 * at the one that replaced it, so what was on board last year can still be
 * shown (DOC-06). Removing marks the row and leaves the trail.
 */
@Entity
@Table(name = "document")
class Document extends BaseEntity implements VesselScoped {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 24)
    private OwnerType ownerType;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 24)
    private DocumentType documentType;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "certificate_number", length = 80)
    private String certificateNumber;

    @Column(name = "issuing_authority", length = 160)
    private String issuingAuthority;

    @Column(name = "issued_date")
    private LocalDate issuedDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 120)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false, length = 80)
    private String storageKey;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "uploaded_by_user_id", nullable = false)
    private Long uploadedByUserId;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "superseded_by_id")
    private Long supersededById;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "removed_by_user_id")
    private Long removedByUserId;

    protected Document() {
    }

    Document(Long organizationId, Long vesselId, OwnerType ownerType, Long ownerId, DocumentType documentType,
             String title, String fileName, String contentType, long sizeBytes, String storageKey, String sha256,
             Long uploadedByUserId, Instant uploadedAt) {
        this.organizationId = organizationId;
        this.vesselId = vesselId;
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.documentType = documentType;
        this.title = title;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
        this.sha256 = sha256;
        this.uploadedByUserId = uploadedByUserId;
        this.uploadedAt = uploadedAt;
    }

    @Override
    public Long getVesselId() { return vesselId; }

    Long getOrganizationId() { return organizationId; }
    OwnerType getOwnerType() { return ownerType; }
    Long getOwnerId() { return ownerId; }
    DocumentType getDocumentType() { return documentType; }
    String getTitle() { return title; }
    String getCertificateNumber() { return certificateNumber; }
    String getIssuingAuthority() { return issuingAuthority; }
    LocalDate getIssuedDate() { return issuedDate; }
    LocalDate getExpiryDate() { return expiryDate; }
    String getFileName() { return fileName; }
    String getContentType() { return contentType; }
    long getSizeBytes() { return sizeBytes; }
    String getStorageKey() { return storageKey; }
    String getSha256() { return sha256; }
    Long getUploadedByUserId() { return uploadedByUserId; }
    Instant getUploadedAt() { return uploadedAt; }
    Long getSupersededById() { return supersededById; }
    Instant getRemovedAt() { return removedAt; }

    boolean isCurrent() { return removedAt == null && supersededById == null; }

    void describeCertificate(String number, String authority, LocalDate issued, LocalDate expiry) {
        this.certificateNumber = number;
        this.issuingAuthority = authority;
        this.issuedDate = issued;
        this.expiryDate = expiry;
    }

    void supersededBy(Long documentId) {
        this.supersededById = documentId;
    }

    void removed(Long userId, Instant at) {
        this.removedByUserId = userId;
        this.removedAt = at;
    }
}
