package com.seastella.identity.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Links a user to a vessel they may access.
 *
 * <p>{@code assignedByUserId} is not decoration: SoW section 4.1 delegates
 * provisioning down a chain (Platform Admin to Technical Head to Ship Manager),
 * and recording who granted each assignment is what makes that chain auditable
 * after the fact.
 */
@Entity
@Table(name = "user_vessel_assignment",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_vessel",
                columnNames = {"user_id", "vessel_id"}))
public class UserVesselAssignment extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "vessel_id", nullable = false)
    private Long vesselId;

    @Column(name = "assigned_by_user_id")
    private Long assignedByUserId;

    protected UserVesselAssignment() {
    }

    public UserVesselAssignment(Long userId, Long vesselId, Long assignedByUserId) {
        this.userId = userId;
        this.vesselId = vesselId;
        this.assignedByUserId = assignedByUserId;
    }

    public Long getUserId() { return userId; }
    public Long getVesselId() { return vesselId; }
    public Long getAssignedByUserId() { return assignedByUserId; }
}
