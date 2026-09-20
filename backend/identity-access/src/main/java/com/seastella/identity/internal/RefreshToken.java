package com.seastella.identity.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One refresh token, stored as a hash. The raw value exists only in the
 * user's cookie.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Column(name = "family_started_at", nullable = false)
    private Instant familyStartedAt;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 32)
    private String revokedReason;

    @Column(name = "replaced_by_id")
    private Long replacedById;

    @Column(name = "created_by_ip", length = 45)
    private String createdByIp;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    protected RefreshToken() {
    }

    RefreshToken(Long userId, String tokenHash, String familyId, Instant familyStartedAt,
                 Instant issuedAt, Instant expiresAt, String createdByIp, String userAgent) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.familyStartedAt = familyStartedAt;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.createdByIp = createdByIp;
        this.userAgent = userAgent;
    }

    Long getUserId() { return userId; }
    String getFamilyId() { return familyId; }
    Instant getFamilyStartedAt() { return familyStartedAt; }
    Instant getExpiresAt() { return expiresAt; }
    Instant getRevokedAt() { return revokedAt; }
    String getRevokedReason() { return revokedReason; }

    boolean isRevoked() { return revokedAt != null; }

    boolean isExpired(Instant now) { return !expiresAt.isAfter(now); }

    void revoke(String reason, Instant at) {
        if (revokedAt == null) {
            revokedAt = at;
            revokedReason = reason;
        }
    }

    void rotatedInto(Long successorId, Instant at) {
        revoke("ROTATED", at);
        replacedById = successorId;
    }
}
