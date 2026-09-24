package com.seastella.fleet.internal;

import com.seastella.core.api.model.BaseEntity;
import com.seastella.core.api.model.VesselScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * One service performed on a Spare (SoW §6.3).
 *
 * <p>Either the platform watched it happen — a request was completed, and the
 * record carries its number — or someone recorded it afterwards, typically
 * work done before this platform existed or by a contractor who has no account
 * on it. The two are told apart because that is the first question anyone
 * reviewing a history asks.
 */
@Entity
@Table(name = "spare_service_record")
public class SpareServiceRecord extends BaseEntity implements VesselScoped {

    static final String PLATFORM = "PLATFORM";
    static final String RECORDED = "RECORDED";

    @Column(name = "spare_id", nullable = false)
    private Long spareId;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "work_performed", nullable = false, length = 2000)
    private String workPerformed;

    @Column(name = "parts_used", length = 1000)
    private String partsUsed;

    @Column(name = "performed_by", length = 200)
    private String performedBy;

    @Column(name = "service_request_id")
    private Long serviceRequestId;

    @Column(name = "request_number", length = 60)
    private String requestNumber;

    @Column(name = "recorded_by_user_id")
    private Long recordedByUserId;

    @Column(name = "notes", length = 1000)
    private String notes;

    protected SpareServiceRecord() {
    }

    /** Written when a service request completes; carries the request it came from. */
    static SpareServiceRecord fromRequest(Long spareId, Long vesselId, LocalDate serviceDate, String workPerformed,
                                          String partsUsed, String performedBy, Long serviceRequestId,
                                          String requestNumber) {
        SpareServiceRecord r = new SpareServiceRecord();
        r.spareId = spareId;
        r.vesselId = vesselId;
        r.serviceDate = serviceDate;
        r.source = PLATFORM;
        r.workPerformed = workPerformed;
        r.partsUsed = partsUsed;
        r.performedBy = performedBy;
        r.serviceRequestId = serviceRequestId;
        r.requestNumber = requestNumber;
        return r;
    }

    /** Entered by hand: history that predates the platform, or work done outside it. */
    static SpareServiceRecord recorded(Long spareId, Long vesselId, LocalDate serviceDate, String workPerformed,
                                       String partsUsed, String performedBy, String notes, Long recordedByUserId) {
        SpareServiceRecord r = new SpareServiceRecord();
        r.spareId = spareId;
        r.vesselId = vesselId;
        r.serviceDate = serviceDate;
        r.source = RECORDED;
        r.workPerformed = workPerformed;
        r.partsUsed = partsUsed;
        r.performedBy = performedBy;
        r.notes = notes;
        r.recordedByUserId = recordedByUserId;
        return r;
    }

    @Override
    public Long getVesselId() { return vesselId; }

    Long getSpareId() { return spareId; }
    LocalDate getServiceDate() { return serviceDate; }
    String getSource() { return source; }
    boolean isRecorded() { return RECORDED.equals(source); }
    String getWorkPerformed() { return workPerformed; }
    String getPartsUsed() { return partsUsed; }
    String getPerformedBy() { return performedBy; }
    Long getServiceRequestId() { return serviceRequestId; }
    String getRequestNumber() { return requestNumber; }
    Long getRecordedByUserId() { return recordedByUserId; }
    String getNotes() { return notes; }
}
