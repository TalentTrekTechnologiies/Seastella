package com.seastella.servicerequest.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A problem category for an equipment category. The Captain picks one when
 * raising a request; it selects the troubleshooting flow (SoW s6.1).
 */
@Entity
@Table(name = "problem_type")
public class ProblemType extends BaseEntity {

    @Column(name = "equipment_category_id", nullable = false)
    private Long equipmentCategoryId;

    @Column(name = "code", nullable = false, length = 60)
    private String code;

    @Column(name = "label", nullable = false, length = 160)
    private String label;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected ProblemType() {
    }

    public ProblemType(Long equipmentCategoryId, String code, String label, int displayOrder) {
        this.equipmentCategoryId = equipmentCategoryId;
        this.code = code;
        this.label = label;
        this.displayOrder = displayOrder;
    }

    public Long getEquipmentCategoryId() { return equipmentCategoryId; }
    public String getCode() { return code; }
    public String getLabel() { return label; }
    public int getDisplayOrder() { return displayOrder; }
}
