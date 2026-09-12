package com.seastella.fleet.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * An equipment <em>category</em> - Radar, ECDIS, GPS, EPIRB and so on.
 *
 * <p>SoW section 9.2 is explicit that this is "used to group Spares for
 * dashboards and reports; <b>not itself a serviceable record</b>." So there is
 * deliberately no serial number, no running hours, no service history, and no
 * vessel foreign key here. Every one of those lives on {@link Spare}.
 *
 * <p>This is a global lookup, shared across all organizations.
 */
@Entity
@Table(name = "equipment_category")
public class EquipmentCategory extends BaseEntity {

    @Column(name = "code", nullable = false, unique = true, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected EquipmentCategory() {
    }

    public EquipmentCategory(String code, String name, int displayOrder) {
        this.code = code;
        this.name = name;
        this.displayOrder = displayOrder;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public int getDisplayOrder() { return displayOrder; }
}
