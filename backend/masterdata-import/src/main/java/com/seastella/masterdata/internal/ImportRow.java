package com.seastella.masterdata.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * One row of an uploaded sheet, staged: what it says, what it would do, and -
 * once committed - what it did. Production data is never touched until the
 * batch is committed (IMP-09).
 */
@Entity
@Table(name = "import_row")
class ImportRow extends BaseEntity {

    /** What this row would do, decided while previewing (IMP-05). */
    enum Outcome { NEW, MODIFIED, UNCHANGED, INVALID, DUPLICATE }

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Column(name = "imo_number", length = 16)
    private String imoNumber;

    @Column(name = "vmp_ref", length = 32)
    private String vmpRef;

    @Column(name = "spare_name", length = 200)
    private String spareName;

    @Column(name = "vessel_id")
    private Long vesselId;

    @Column(name = "spare_id")
    private Long spareId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private Outcome outcome;

    @Column(name = "messages", length = 1000)
    private String messages;

    @Column(name = "values_json", nullable = false, length = 4000)
    private String valuesJson;

    @Column(name = "changes_json", length = 4000)
    private String changesJson;

    @Column(name = "applied", nullable = false)
    private boolean applied;

    protected ImportRow() {
    }

    ImportRow(Long batchId, int rowNumber, String imoNumber, String vmpRef, String spareName,
              Long vesselId, Long spareId, Outcome outcome, String messages, String valuesJson, String changesJson) {
        this.batchId = batchId;
        this.rowNumber = rowNumber;
        this.imoNumber = imoNumber;
        this.vmpRef = vmpRef;
        this.spareName = spareName;
        this.vesselId = vesselId;
        this.spareId = spareId;
        this.outcome = outcome;
        this.messages = messages;
        this.valuesJson = valuesJson;
        this.changesJson = changesJson;
    }

    Long getBatchId() { return batchId; }
    int getRowNumber() { return rowNumber; }
    String getImoNumber() { return imoNumber; }
    String getVmpRef() { return vmpRef; }
    String getSpareName() { return spareName; }
    Long getVesselId() { return vesselId; }
    Long getSpareId() { return spareId; }
    Outcome getOutcome() { return outcome; }
    String getMessages() { return messages; }
    String getValuesJson() { return valuesJson; }
    String getChangesJson() { return changesJson; }
    boolean isApplied() { return applied; }

    void appliedAs(Long spareId) {
        this.spareId = spareId;
        this.applied = true;
    }
}
