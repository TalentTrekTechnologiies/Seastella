package com.seastella.identity.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;

/** A single-use link: an invitation to set a first password, or a password reset. */
@Entity
@Table(name = "user_token")
public class UserToken extends BaseEntity {

    public enum Purpose { INVITATION, PASSWORD_RESET }

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 24)
    private Purpose purpose;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    protected UserToken() {
    }

    UserToken(Long userId, Purpose purpose, String tokenHash, Instant expiresAt, Long createdByUserId) {
        this.userId = userId;
        this.purpose = purpose;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdByUserId = createdByUserId;
    }

    Long getUserId() { return userId; }
    Purpose getPurpose() { return purpose; }
    Instant getExpiresAt() { return expiresAt; }

    boolean isUsable(Instant now) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    void use(Instant at) { this.usedAt = at; }

    void revoke(Instant at) {
        if (usedAt == null && revokedAt == null) this.revokedAt = at;
    }
}
