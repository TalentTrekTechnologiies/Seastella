package com.seastella.identity.internal;

import com.seastella.core.api.model.BaseEntity;
import com.seastella.identity.api.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A platform user. Exactly one role, and - except for the Platform Admin -
 * exactly one organization.
 *
 * <p>The organization rule is also a database CHECK constraint, so no code path
 * can create an unscoped tenant user.
 */
@Entity
@Table(name = "app_user")
public class AppUser extends BaseEntity {

    @Column(name = "email", nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private Role role;

    /** Null only for PLATFORM_ADMIN. */
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "seed_marker", length = 8)
    private String seedMarker;

    protected AppUser() {
    }

    public AppUser(String email, String passwordHash, String fullName, Role role, Long organizationId) {
        this.email = email == null ? null : email.trim().toLowerCase();
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.organizationId = organizationId;
    }

    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getFullName() { return fullName; }
    public Role getRole() { return role; }
    public Long getOrganizationId() { return organizationId; }
    public String getStatus() { return status; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public int getFailedLoginCount() { return failedLoginCount; }
    public Instant getLockedUntil() { return lockedUntil; }
    public String getSeedMarker() { return seedMarker; }

    public boolean isActive() { return "ACTIVE".equals(status); }

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    public void markSeed() { this.seedMarker = "SEED"; }

    public void setFullName(String fullName) { this.fullName = fullName; }

    public void setStatus(String status) { this.status = status; }

    public void changePassword(String newHash) {
        this.passwordHash = newHash;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    public void recordSuccessfulLogin() {
        this.lastLoginAt = Instant.now();
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    /** @return true when this failure crossed the threshold and locked the account */
    public boolean recordFailedLogin(int maxAttempts, java.time.Duration lockFor) {
        this.failedLoginCount++;
        if (this.failedLoginCount >= maxAttempts) {
            this.lockedUntil = Instant.now().plus(lockFor);
            this.failedLoginCount = 0;
            return true;
        }
        return false;
    }
}
