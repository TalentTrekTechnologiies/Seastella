package com.seastella.fleet.internal;

import com.seastella.core.api.model.BaseEntity;
import com.seastella.core.api.model.VesselScoped;
import com.seastella.fleet.api.Criticality;
import com.seastella.fleet.api.SpareStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <b>The serviceable asset.</b>
 *
 * <p>SoW section 9: "the individual serviceable item within that category -
 * whether a full unit or a component of one - is a 'Spare,' and it is the
 * Spare, not the category, that carries a service history."
 *
 * <p>Spares nest recursively, mirroring the VMP template's decimal ids:
 * {@code 13 Radar} (category) to {@code 13.1 X-Band Radar} (spare) to
 * {@code 13.1.2 Display Fan} (spare). {@link #path} holds that decimal id and
 * doubles as a materialised path, so a whole subtree is one indexed
 * {@code LIKE 'path.%'} read rather than a recursive walk.
 *
 * <p>A tree may never span two vessels - a database trigger enforces it
 * (test S-07), because a child on another vessel would be a silent route around
 * vessel isolation.
 */
@Entity
@Table(name = "spare", uniqueConstraints = {
        @UniqueConstraint(name = "uk_spare_vessel_path", columnNames = {"vessel_id", "path"}),
        @UniqueConstraint(name = "uk_spare_vessel_vmpref", columnNames = {"vessel_id", "vmp_ref"})
})
public class Spare extends BaseEntity implements VesselScoped {

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Column(name = "equipment_category_id", nullable = false)
    private Long equipmentCategoryId;

    @Column(name = "parent_spare_id")
    private Long parentSpareId;

    /** VMP decimal id, e.g. "13.1.2". Unique per vessel. */
    @Column(name = "path", nullable = false, length = 255)
    private String path;

    @Column(name = "depth", nullable = false)
    private short depth;

    @Column(name = "vmp_ref", length = 32)
    private String vmpRef;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "make", length = 120)
    private String make;

    @Column(name = "model", length = 120)
    private String model;

    @Column(name = "serial_number", length = 120)
    private String serialNumber;

    @Column(name = "software_version", length = 64)
    private String softwareVersion;

    @Column(name = "installation_date")
    private LocalDate installationDate;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    @Column(name = "last_annual_service_date")
    private LocalDate lastAnnualServiceDate;

    @Column(name = "last_survey_date")
    private LocalDate lastSurveyDate;

    /** Annual Performance Test. */
    @Column(name = "last_apt_date")
    private LocalDate lastAptDate;

    @Column(name = "running_hours", precision = 12, scale = 2)
    private BigDecimal runningHours;

    @Column(name = "tracks_running_hours", nullable = false)
    private boolean tracksRunningHours;

    @Enumerated(EnumType.STRING)
    @Column(name = "criticality", nullable = false, length = 16)
    private Criticality criticality = Criticality.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private SpareStatus status = SpareStatus.OPERATIONAL;

    @Column(name = "seed_marker", length = 8)
    private String seedMarker;

    protected Spare() {
    }

    public Spare(Long vesselId, Long equipmentCategoryId, String path, String name) {
        this.vesselId = vesselId;
        this.equipmentCategoryId = equipmentCategoryId;
        this.path = path;
        this.vmpRef = path;
        this.name = name;
        this.depth = (short) (path.chars().filter(c -> c == '.').count());
    }

    @Override
    public Long getVesselId() { return vesselId; }

    public Long getEquipmentCategoryId() { return equipmentCategoryId; }
    public Long getParentSpareId() { return parentSpareId; }
    public String getPath() { return path; }
    public short getDepth() { return depth; }
    public String getVmpRef() { return vmpRef; }
    public String getName() { return name; }
    public String getMake() { return make; }
    public String getModel() { return model; }
    public String getSerialNumber() { return serialNumber; }
    public String getSoftwareVersion() { return softwareVersion; }
    public LocalDate getInstallationDate() { return installationDate; }
    public LocalDate getExpirationDate() { return expirationDate; }
    public LocalDate getLastAnnualServiceDate() { return lastAnnualServiceDate; }
    public LocalDate getLastSurveyDate() { return lastSurveyDate; }
    public LocalDate getLastAptDate() { return lastAptDate; }
    public BigDecimal getRunningHours() { return runningHours; }
    public boolean isTracksRunningHours() { return tracksRunningHours; }
    public Criticality getCriticality() { return criticality; }
    public SpareStatus getStatus() { return status; }
    public String getSeedMarker() { return seedMarker; }

    public void setParentSpareId(Long id) { this.parentSpareId = id; }
    public void setMake(String v) { this.make = v; }
    public void setModel(String v) { this.model = v; }
    public void setSerialNumber(String v) { this.serialNumber = v; }
    public void setSoftwareVersion(String v) { this.softwareVersion = v; }
    public void setInstallationDate(LocalDate d) { this.installationDate = d; }
    public void setExpirationDate(LocalDate d) { this.expirationDate = d; }
    public void setLastAnnualServiceDate(LocalDate d) { this.lastAnnualServiceDate = d; }
    public void setLastSurveyDate(LocalDate d) { this.lastSurveyDate = d; }
    public void setLastAptDate(LocalDate d) { this.lastAptDate = d; }
    public void setCriticality(Criticality c) { this.criticality = c; }
    public void setStatus(SpareStatus s) { this.status = s; }
    public void markSeed() { this.seedMarker = "SEED"; }

    public void enableRunningHours(BigDecimal initial) {
        this.tracksRunningHours = true;
        this.runningHours = initial;
    }

    public void updateRunningHours(BigDecimal reading) {
        this.runningHours = reading;
    }
}
