package com.seastella.fleet.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * <b>The version an equipment model ought to be running.</b>
 *
 * <p>The vessel side of this comparison already exists: {@link Spare} records
 * the software version installed on the unit. This is the other side - the
 * client's master sheet, one row per equipment model, naming the latest
 * release the manufacturer has shipped.
 *
 * <p>A baseline belongs to a <em>model</em>, not to a vessel and not to an
 * equipment category. Every Furuno FA-170 in the fleet is measured against the
 * same release, which is why this table is unscoped and why the same row
 * serves twenty vessels.
 *
 * <p>The match is on {@link #matchKey}, a folded make and model - see
 * {@link SoftwareMatchKey} for what is folded and why. The key is stored, not
 * derived at read time, so the uniqueness of "one model, one baseline" is the
 * database's to enforce rather than the importer's to remember.
 */
@Entity
@Table(name = "software_baseline", uniqueConstraints = {
        @UniqueConstraint(name = "uk_software_baseline_key", columnNames = {"match_key"})
})
public class SoftwareBaseline extends BaseEntity {

    /** How a baseline row came to be here. */
    public enum Source {
        /** Written by the master-sheet upload. Replaced by the next upload. */
        IMPORTED,
        /** Typed or corrected by hand, and left alone by an upload. */
        RECORDED
    }

    @Column(name = "make", nullable = false, length = 120)
    private String make;

    @Column(name = "model", nullable = false, length = 120)
    private String model;

    @Column(name = "match_key", nullable = false, length = 260)
    private String matchKey;

    /** What the client's sheet called this row. Display only. */
    @Column(name = "equipment_name", length = 200)
    private String equipmentName;

    @Column(name = "latest_version", nullable = false, length = 64)
    private String latestVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private Source source = Source.IMPORTED;

    @Column(name = "notes", length = 500)
    private String notes;

    protected SoftwareBaseline() {
    }

    public SoftwareBaseline(String make, String model, String equipmentName,
                            String latestVersion, Source source) {
        this.make = make;
        this.model = model;
        this.equipmentName = equipmentName;
        this.latestVersion = latestVersion;
        this.source = source;
        this.matchKey = SoftwareMatchKey.of(make, model);
    }

    public String getMake() { return make; }
    public String getModel() { return model; }
    public String getMatchKey() { return matchKey; }
    public String getEquipmentName() { return equipmentName; }
    public String getLatestVersion() { return latestVersion; }
    public Source getSource() { return source; }
    public String getNotes() { return notes; }

    /**
     * Re-points this baseline at a different model. The key moves with it,
     * because a key that no longer matches its own make and model would match
     * some other equipment's spares.
     */
    public void setMakeAndModel(String make, String model) {
        this.make = make;
        this.model = model;
        this.matchKey = SoftwareMatchKey.of(make, model);
    }

    public void setEquipmentName(String equipmentName) { this.equipmentName = equipmentName; }
    public void setLatestVersion(String latestVersion) { this.latestVersion = latestVersion; }
    public void setSource(Source source) { this.source = source; }
    public void setNotes(String notes) { this.notes = notes; }
}
