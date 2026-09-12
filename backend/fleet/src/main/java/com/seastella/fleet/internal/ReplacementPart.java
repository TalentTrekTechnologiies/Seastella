package com.seastella.fleet.internal;

import com.seastella.core.api.model.BaseEntity;
import com.seastella.core.api.model.VesselScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * Consumable stock held aboard for swapping into a {@link Spare}.
 *
 * <p><b>Not the same thing as a Spare.</b> SoW section 7 defines this as
 * "backup/replacement parts kept for swapping into a Spare - distinct from the
 * Spare master record itself." The Spare is the installed, serviceable item and
 * carries the service history; this is inventory. Merging them would break both.
 *
 * <p>View-only for the MVP (variance V-10): quantities arrive through the VMP
 * import, and the shortage flag is derived rather than stored so it cannot drift
 * from the quantity it describes.
 */
@Entity
@Table(name = "replacement_part")
public class ReplacementPart extends BaseEntity implements VesselScoped {

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    /** The spare this part swaps into; null when held as general stock. */
    @Column(name = "spare_id")
    private Long spareId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "part_number", length = 120)
    private String partNumber;

    @Column(name = "manufacturer", length = 120)
    private String manufacturer;

    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand;

    @Column(name = "minimum_quantity", nullable = false)
    private int minimumQuantity;

    @Column(name = "location", length = 120)
    private String location;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "seed_marker", length = 8)
    private String seedMarker;

    protected ReplacementPart() {
    }

    public ReplacementPart(Long vesselId, String name, int quantityOnHand, int minimumQuantity) {
        this.vesselId = vesselId;
        this.name = name;
        this.quantityOnHand = quantityOnHand;
        this.minimumQuantity = minimumQuantity;
    }

    @Override
    public Long getVesselId() { return vesselId; }

    public Long getSpareId() { return spareId; }
    public String getName() { return name; }
    public String getPartNumber() { return partNumber; }
    public String getManufacturer() { return manufacturer; }
    public int getQuantityOnHand() { return quantityOnHand; }
    public int getMinimumQuantity() { return minimumQuantity; }
    public String getLocation() { return location; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public String getSeedMarker() { return seedMarker; }

    /** Derived, never stored - see the class note. */
    public boolean isBelowMinimum() {
        return quantityOnHand < minimumQuantity;
    }

    public void setSpareId(Long id) { this.spareId = id; }
    public void setPartNumber(String v) { this.partNumber = v; }
    public void setManufacturer(String v) { this.manufacturer = v; }
    public void setLocation(String v) { this.location = v; }
    public void setExpiryDate(LocalDate d) { this.expiryDate = d; }
    public void markSeed() { this.seedMarker = "SEED"; }
}
