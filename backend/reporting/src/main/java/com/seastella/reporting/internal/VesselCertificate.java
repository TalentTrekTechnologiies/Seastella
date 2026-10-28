package com.seastella.reporting.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * An issued Shore Based Maintenance Certificate. Never deleted: a new one for
 * the same vessel supersedes it, and a wrong one is revoked with a reason.
 */
@Entity
@Table(name = "vessel_certificate")
class VesselCertificate extends BaseEntity {

    static final String SBMC = "SBMC";

    enum Status { ACTIVE, SUPERSEDED, REVOKED }

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "certificate_type", nullable = false, length = 16)
    private String certificateType;

    @Column(name = "certificate_number", nullable = false, length = 32)
    private String certificateNumber;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** Every value printed, as JSON - the certificate's own record of what it says. */
    @Column(name = "field_snapshot", nullable = false, length = 4000)
    private String fieldSnapshot;

    @Column(name = "template_version", nullable = false, length = 32)
    private String templateVersion;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "generated_by_user_id", nullable = false)
    private Long generatedByUserId;

    @Column(name = "generated_by_role", nullable = false, length = 32)
    private String generatedByRole;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "superseded_by_id")
    private Long supersededById;

    @Column(name = "revoked_reason", length = 500)
    private String revokedReason;

    @Column(name = "revoked_by_user_id")
    private Long revokedByUserId;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected VesselCertificate() {
    }

    VesselCertificate(Long vesselId, Long organizationId, String certificateNumber, LocalDate issueDate,
                      LocalDate expiryDate, String fieldSnapshot, String templateVersion, Long documentId,
                      Long generatedByUserId, String generatedByRole, Instant generatedAt) {
        this.vesselId = vesselId;
        this.organizationId = organizationId;
        this.certificateType = SBMC;
        this.certificateNumber = certificateNumber;
        this.issueDate = issueDate;
        this.expiryDate = expiryDate;
        this.status = Status.ACTIVE.name();
        this.fieldSnapshot = fieldSnapshot;
        this.templateVersion = templateVersion;
        this.documentId = documentId;
        this.generatedByUserId = generatedByUserId;
        this.generatedByRole = generatedByRole;
        this.generatedAt = generatedAt;
    }

    Long getVesselId() { return vesselId; }
    Long getOrganizationId() { return organizationId; }
    String getCertificateType() { return certificateType; }
    String getCertificateNumber() { return certificateNumber; }
    LocalDate getIssueDate() { return issueDate; }
    LocalDate getExpiryDate() { return expiryDate; }
    Status getStatus() { return Status.valueOf(status); }
    String getFieldSnapshot() { return fieldSnapshot; }
    String getTemplateVersion() { return templateVersion; }
    Long getDocumentId() { return documentId; }
    Long getGeneratedByUserId() { return generatedByUserId; }
    String getGeneratedByRole() { return generatedByRole; }
    Instant getGeneratedAt() { return generatedAt; }
    Long getSupersededById() { return supersededById; }
    String getRevokedReason() { return revokedReason; }
    Long getRevokedByUserId() { return revokedByUserId; }
    Instant getRevokedAt() { return revokedAt; }

    void supersededBy(Long certificateId) {
        this.status = Status.SUPERSEDED.name();
        this.supersededById = certificateId;
    }

    void revoke(String reason, Long userId, Instant at) {
        this.status = Status.REVOKED.name();
        this.revokedReason = reason;
        this.revokedByUserId = userId;
        this.revokedAt = at;
    }
}
