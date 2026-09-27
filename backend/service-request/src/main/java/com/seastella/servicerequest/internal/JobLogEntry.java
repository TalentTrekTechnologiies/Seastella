package com.seastella.servicerequest.internal;

import com.seastella.core.api.model.BaseEntity;
import com.seastella.core.api.model.VesselScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/** One line of an engineer's job log: what happened on the job, and when. */
@Entity
@Table(name = "job_log_entry")
public class JobLogEntry extends BaseEntity implements VesselScoped {

    /** What the entry records. STARTED and REPORTED are also written by the workflow itself. */
    public enum Kind { ARRIVED, STARTED, UPDATE, WAITING, RESUMED, FINISHED, LEFT, REPORTED }

    @Column(name = "service_request_id", nullable = false)
    private Long serviceRequestId;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Column(name = "author_user_id", nullable = false)
    private Long authorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "note", length = 2000)
    private String note;

    @Column(name = "document_id")
    private Long documentId;

    @Column(name = "automatic", nullable = false)
    private boolean automatic;

    protected JobLogEntry() {
    }

    JobLogEntry(Long serviceRequestId, Long vesselId, Long authorUserId, Kind kind, Instant occurredAt,
                String note, Long documentId, boolean automatic) {
        this.serviceRequestId = serviceRequestId;
        this.vesselId = vesselId;
        this.authorUserId = authorUserId;
        this.kind = kind;
        this.occurredAt = occurredAt;
        this.note = note;
        this.documentId = documentId;
        this.automatic = automatic;
    }

    @Override public Long getVesselId() { return vesselId; }
    Long getServiceRequestId() { return serviceRequestId; }
    Long getAuthorUserId() { return authorUserId; }
    Kind getKind() { return kind; }
    Instant getOccurredAt() { return occurredAt; }
    String getNote() { return note; }
    Long getDocumentId() { return documentId; }
    boolean isAutomatic() { return automatic; }
}
