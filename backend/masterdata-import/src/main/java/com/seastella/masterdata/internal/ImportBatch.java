package com.seastella.masterdata.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One uploaded spreadsheet: what it contained, who handled it, and what became
 * of it. Kept after the fact as the upload history (IMP-10).
 */
@Entity
@Table(name = "import_batch")
class ImportBatch extends BaseEntity {

    enum Status { PREVIEW, COMMITTED, DISCARDED }

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PREVIEW;

    @Column(name = "uploaded_by_user_id", nullable = false)
    private Long uploadedByUserId;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "previewed_by_user_id")
    private Long previewedByUserId;

    @Column(name = "previewed_at")
    private Instant previewedAt;

    @Column(name = "committed_by_user_id")
    private Long committedByUserId;

    @Column(name = "committed_at")
    private Instant committedAt;

    @Column(name = "discarded_at")
    private Instant discardedAt;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "new_count", nullable = false)
    private int newCount;

    @Column(name = "modified_count", nullable = false)
    private int modifiedCount;

    @Column(name = "unchanged_count", nullable = false)
    private int unchangedCount;

    @Column(name = "invalid_count", nullable = false)
    private int invalidCount;

    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount;

    @Column(name = "applied_count")
    private Integer appliedCount;

    @Column(name = "vessels_summary", length = 500)
    private String vesselsSummary;

    protected ImportBatch() {
    }

    ImportBatch(String fileName, long fileSize, Long uploadedByUserId, Instant uploadedAt) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.uploadedByUserId = uploadedByUserId;
        this.uploadedAt = uploadedAt;
    }

    String getFileName() { return fileName; }
    long getFileSize() { return fileSize; }
    Status getStatus() { return status; }
    Long getUploadedByUserId() { return uploadedByUserId; }
    Instant getUploadedAt() { return uploadedAt; }
    Long getPreviewedByUserId() { return previewedByUserId; }
    Instant getPreviewedAt() { return previewedAt; }
    Long getCommittedByUserId() { return committedByUserId; }
    Instant getCommittedAt() { return committedAt; }
    Instant getDiscardedAt() { return discardedAt; }
    int getRowCount() { return rowCount; }
    int getNewCount() { return newCount; }
    int getModifiedCount() { return modifiedCount; }
    int getUnchangedCount() { return unchangedCount; }
    int getInvalidCount() { return invalidCount; }
    int getDuplicateCount() { return duplicateCount; }
    Integer getAppliedCount() { return appliedCount; }
    String getVesselsSummary() { return vesselsSummary; }

    /** Rows that would change something if this batch were committed. */
    int getApplicableCount() { return newCount + modifiedCount; }

    void summarise(int rows, int created, int modified, int unchanged, int invalid, int duplicate, String vessels) {
        this.rowCount = rows;
        this.newCount = created;
        this.modifiedCount = modified;
        this.unchangedCount = unchanged;
        this.invalidCount = invalid;
        this.duplicateCount = duplicate;
        this.vesselsSummary = vessels;
    }

    void previewedBy(Long userId, Instant at) {
        this.previewedByUserId = userId;
        this.previewedAt = at;
    }

    void committed(Long userId, Instant at, int applied) {
        this.status = Status.COMMITTED;
        this.committedByUserId = userId;
        this.committedAt = at;
        this.appliedCount = applied;
    }

    void discarded(Instant at) {
        this.status = Status.DISCARDED;
        this.discardedAt = at;
    }
}
