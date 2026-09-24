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

    /**
     * What the row is about. One workbook can hold all three - the vessel's
     * particulars, its equipment, and a minimum-spares table - and each is
     * applied to a different place on commit.
     */
    enum Kind { VESSEL, EQUIPMENT, CRITICAL_SPARE }

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind = Kind.EQUIPMENT;

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

    @Column(name = "part_id")
    private Long partId;

    /** The equipment a critical spare names, as the sheet wrote it. */
    @Column(name = "equipment_label", length = 200)
    private String equipmentLabel;

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

    ImportRow(Long batchId, Kind kind, int rowNumber, String imoNumber, String vmpRef, String spareName,
              String equipmentLabel, Long vesselId, Long spareId, Long partId, Outcome outcome,
              String messages, String valuesJson, String changesJson) {
        this.batchId = batchId;
        this.kind = kind;
        this.equipmentLabel = equipmentLabel;
        this.partId = partId;
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
    Kind getKind() { return kind; }
    Long getPartId() { return partId; }
    String getEquipmentLabel() { return equipmentLabel; }
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

    void appliedAsPart(Long partId) {
        this.partId = partId;
        this.applied = true;
    }

    /** The vessel row changes the vessel itself; there is no new id to keep. */
    void appliedToVessel() {
        this.applied = true;
    }
}
