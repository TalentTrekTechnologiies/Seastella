package com.seastella.troubleshooting.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One version of a set of guided checks for an equipment category and problem
 * type (SoW s13).
 *
 * <p>Versions of the same flow share a {@code code}. Only a {@link Status#DRAFT}
 * is edited; publishing it retires the previous published version. Published
 * and retired versions are frozen, because sessions and their logged answers
 * point at their steps.
 */
@Entity
@Table(name = "troubleshooting_flow")
public class TroubleshootingFlow extends BaseEntity {

    static final String SAMPLE = "SAMPLE";
    static final String SEASTELLA = "SEASTELLA";

    enum Status { DRAFT, PUBLISHED, RETIRED }

    @Column(name = "equipment_category_id")
    private Long equipmentCategoryId;

    @Column(name = "problem_type_id")
    private Long problemTypeId;

    @Column(name = "code", nullable = false, length = 60)
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "flow_version", nullable = false)
    private int flowVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.DRAFT;

    @Column(name = "content_source", nullable = false, length = 16)
    private String contentSource;

    @Column(name = "start_step_key", nullable = false, length = 40)
    private String startStepKey;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by_user_id")
    private Long publishedByUserId;

    @Column(name = "retired_at")
    private Instant retiredAt;

    protected TroubleshootingFlow() {
    }

    /** A new draft. */
    TroubleshootingFlow(Long equipmentCategoryId, Long problemTypeId, String code, String name,
                        int flowVersion, String contentSource, String startStepKey) {
        this.equipmentCategoryId = equipmentCategoryId;
        this.problemTypeId = problemTypeId;
        this.code = code;
        this.name = name;
        this.flowVersion = flowVersion;
        this.contentSource = contentSource;
        this.startStepKey = startStepKey;
    }

    Long getEquipmentCategoryId() { return equipmentCategoryId; }
    Long getProblemTypeId() { return problemTypeId; }
    String getCode() { return code; }
    String getName() { return name; }
    int getFlowVersion() { return flowVersion; }
    Status getStatus() { return status; }
    boolean isPublished() { return status == Status.PUBLISHED; }
    boolean isDraft() { return status == Status.DRAFT; }
    String getContentSource() { return contentSource; }
    String getStartStepKey() { return startStepKey; }
    Instant getPublishedAt() { return publishedAt; }
    Long getPublishedByUserId() { return publishedByUserId; }
    Instant getRetiredAt() { return retiredAt; }

    void edit(String name, Long equipmentCategoryId, Long problemTypeId, String contentSource, String startStepKey) {
        requireDraft();
        this.name = name;
        this.equipmentCategoryId = equipmentCategoryId;
        this.problemTypeId = problemTypeId;
        this.contentSource = contentSource;
        this.startStepKey = startStepKey;
    }

    void publish(Long byUserId, Instant at) {
        requireDraft();
        this.status = Status.PUBLISHED;
        this.publishedByUserId = byUserId;
        this.publishedAt = at;
    }

    void retire(Instant at) {
        if (status != Status.PUBLISHED) throw new IllegalStateException("Only a published flow can be retired");
        this.status = Status.RETIRED;
        this.retiredAt = at;
    }

    private void requireDraft() {
        if (status != Status.DRAFT) throw new IllegalStateException("Flow " + code + " v" + flowVersion + " is " + status);
    }
}
