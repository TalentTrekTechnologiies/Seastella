package com.seastella.servicerequest.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A problem category for an equipment category. The Captain picks one when
 * starting the guided checks; it selects the troubleshooting flow (SoW s6.1).
 *
 * <p>Maintained by the Platform Admin (s13). Never deleted, because past
 * requests name it; a retired problem type is simply no longer offered.
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

    @Column(name = "active", nullable = false)
    private boolean active = true;

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
    public boolean isActive() { return active; }

    void rename(String label) { this.label = label; }

    void setActive(boolean active) { this.active = active; }

    void moveTo(int displayOrder) { this.displayOrder = displayOrder; }
}
