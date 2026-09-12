package com.seastella.identity.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Links a Seastella-side user to a client organization they are authorised to
 * service (OI-16).
 *
 * <p>Distinct from {@code app_user.organization_id}, which marks membership of
 * a single client tenant. A Service Coordinator is not a member of any client
 * organization; they are Seastella staff assigned to service several. Keeping
 * the two concepts in separate columns is what stops "who employs this person"
 * and "whose data may they touch" from collapsing into one another.
 */
@Entity
@Table(name = "user_organization_assignment",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_organization",
                columnNames = {"user_id", "organization_id"}))
public class UserOrganizationAssignment extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "assigned_by_user_id")
    private Long assignedByUserId;

    protected UserOrganizationAssignment() {
    }

    public UserOrganizationAssignment(Long userId, Long organizationId, Long assignedByUserId) {
        this.userId = userId;
        this.organizationId = organizationId;
        this.assignedByUserId = assignedByUserId;
    }

    public Long getUserId() { return userId; }
    public Long getOrganizationId() { return organizationId; }
    public Long getAssignedByUserId() { return assignedByUserId; }
}
