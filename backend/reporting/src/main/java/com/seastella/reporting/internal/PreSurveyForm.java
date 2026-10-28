package com.seastella.reporting.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/** One exported QM-F11 pre-survey form: what it printed, and what was typed over the records. */
@Entity
@Table(name = "pre_survey_form")
class PreSurveyForm extends BaseEntity {

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "field_snapshot", nullable = false, length = 12000)
    private String fieldSnapshot;

    @Column(name = "overrides", length = 6000)
    private String overrides;

    @Column(name = "remarks", length = 2000)
    private String remarks;

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

    protected PreSurveyForm() {
    }

    PreSurveyForm(Long vesselId, Long organizationId, String fieldSnapshot, String overrides, String remarks,
                  String templateVersion, Long documentId, Long generatedByUserId, String generatedByRole,
                  Instant generatedAt) {
        this.vesselId = vesselId;
        this.organizationId = organizationId;
        this.fieldSnapshot = fieldSnapshot;
        this.overrides = overrides;
        this.remarks = remarks;
        this.templateVersion = templateVersion;
        this.documentId = documentId;
        this.generatedByUserId = generatedByUserId;
        this.generatedByRole = generatedByRole;
        this.generatedAt = generatedAt;
    }

    Long getVesselId() { return vesselId; }
    Long getOrganizationId() { return organizationId; }
    String getFieldSnapshot() { return fieldSnapshot; }
    String getOverrides() { return overrides; }
    String getRemarks() { return remarks; }
    String getTemplateVersion() { return templateVersion; }
    Long getDocumentId() { return documentId; }
    Long getGeneratedByUserId() { return generatedByUserId; }
    String getGeneratedByRole() { return generatedByRole; }
    Instant getGeneratedAt() { return generatedAt; }
}
