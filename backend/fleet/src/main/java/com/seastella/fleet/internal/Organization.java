package com.seastella.fleet.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A Seastella client company. Created by the Platform Admin only (SoW s4.1). */
@Entity
@Table(name = "organization")
public class Organization extends BaseEntity {

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "code", nullable = false, unique = true, length = 24)
    private String code;

    @Column(name = "address", length = 400)
    private String address;

    @Column(name = "contact_email", length = 254)
    private String contactEmail;

    @Column(name = "contact_phone", length = 40)
    private String contactPhone;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "seed_marker", length = 8)
    private String seedMarker;

    protected Organization() {
    }

    public Organization(String name, String code) {
        this.name = name;
        this.code = code;
    }

    public String getName() { return name; }
    public String getCode() { return code; }
    public String getAddress() { return address; }
    public String getContactEmail() { return contactEmail; }
    public String getContactPhone() { return contactPhone; }
    public String getStatus() { return status; }
    public String getSeedMarker() { return seedMarker; }

    public void setName(String name) { this.name = name; }
    public void setAddress(String address) { this.address = address; }
    public void setContactEmail(String e) { this.contactEmail = e; }
    public void setContactPhone(String p) { this.contactPhone = p; }
    public void setStatus(String status) { this.status = status; }
    public void markSeed() { this.seedMarker = "SEED"; }
}
