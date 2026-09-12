package com.seastella.fleet.internal;

import com.seastella.core.api.model.BaseEntity;
import com.seastella.core.api.model.OrganizationScoped;
import com.seastella.fleet.api.VesselStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * A vessel. Field names follow the VMP template (SoW section 9.1) so the Excel
 * import needs no mapping layer.
 */
@Entity
@Table(name = "vessel")
public class Vessel extends BaseEntity implements OrganizationScoped {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** Unique platform-wide. The import resolves vessels by this. */
    @Column(name = "imo_number", nullable = false, unique = true, length = 10)
    private String imoNumber;

    @Column(name = "mmsi", length = 12)
    private String mmsi;

    @Column(name = "call_sign", length = 16)
    private String callSign;

    @Column(name = "flag", length = 64)
    private String flag;

    @Column(name = "vessel_class", length = 64)
    private String vesselClass;

    @Column(name = "area", length = 64)
    private String area;

    @Column(name = "vessel_type", length = 64)
    private String vesselType;

    @Column(name = "dwt", precision = 12, scale = 2)
    private BigDecimal dwt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private VesselStatus status = VesselStatus.ACTIVE;

    @Column(name = "seed_marker", length = 8)
    private String seedMarker;

    protected Vessel() {
    }

    public Vessel(Long organizationId, String name, String imoNumber) {
        this.organizationId = organizationId;
        this.name = name;
        this.imoNumber = imoNumber;
    }

    @Override
    public Long getOrganizationId() { return organizationId; }

    public String getName() { return name; }
    public String getImoNumber() { return imoNumber; }
    public String getMmsi() { return mmsi; }
    public String getCallSign() { return callSign; }
    public String getFlag() { return flag; }
    public String getVesselClass() { return vesselClass; }
    public String getArea() { return area; }
    public String getVesselType() { return vesselType; }
    public BigDecimal getDwt() { return dwt; }
    public VesselStatus getStatus() { return status; }
    public String getSeedMarker() { return seedMarker; }

    public void setName(String n) { this.name = n; }
    public void setMmsi(String m) { this.mmsi = m; }
    public void setCallSign(String c) { this.callSign = c; }
    public void setFlag(String f) { this.flag = f; }
    public void setVesselClass(String c) { this.vesselClass = c; }
    public void setArea(String a) { this.area = a; }
    public void setVesselType(String t) { this.vesselType = t; }
    public void setDwt(BigDecimal d) { this.dwt = d; }
    public void setStatus(VesselStatus s) { this.status = s; }
    public void markSeed() { this.seedMarker = "SEED"; }
}
