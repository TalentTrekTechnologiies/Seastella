package com.seastella.identity.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, rotates and revokes refresh tokens (SEC-02, S-44).
 *
 * <ul>
 *   <li><b>Opaque and hashed.</b> 256 random bits, handed to the browser once
 *       in an httpOnly cookie; only the SHA-256 is stored.</li>
 *   <li><b>Rotated on every use.</b> A refresh returns a new token and retires
 *       the old one.</li>
 *   <li><b>Reuse revokes the chain.</b> A retired token presented again can
 *       only be a copy, so every token from that sign-in is revoked and the
 *       event audited. The real user signs in again; the thief is locked out.</li>
 *   <li><b>Two limits.</b> Idle: unused for the refresh TTL, it expires. Absolute:
 *       one sign-in never outlives the session maximum, however active.</li>
 * </ul>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokens;
    private final AppUserRepository users;
    private final SecurityProperties properties;
    private final AuditService audit;

    RefreshTokenService(RefreshTokenRepository tokens, AppUserRepository users,
                        SecurityProperties properties, AuditService audit) {
        this.tokens = tokens;
        this.users = users;
        this.properties = properties;
        this.audit = audit;
    }

    /** A new raw token and the user it signs in. The raw value is never stored. */
    public record Issued(String rawToken, AppUser user, Duration cookieMaxAge) {}

    /** Starts a new sign-in family for a user who just authenticated with a password. */
    @Transactional(propagation = Propagation.REQUIRED)
    public Issued issueForNewSession(AppUser user, String ip, String userAgent) {
        Instant now = Instant.now();
        return issue(user, UUID.randomUUID().toString(), now, now, ip, userAgent);
    }

    /**
     * Exchanges a refresh token for its successor.
     *
     * @return empty when the token is unknown, expired, revoked, or its user
     *         can no longer sign in
     */
    @Transactional
    public Optional<Issued> rotate(String rawToken, String ip, String userAgent) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        RefreshToken current = tokens.findByTokenHash(hash(rawToken)).orElse(null);
        if (current == null) return Optional.empty();

        Instant now = Instant.now();
        boolean raceWithinGrace = current.isRevoked()
                && "ROTATED".equals(current.getRevokedReason())
                && current.getRevokedAt().isAfter(now.minus(properties.getJwt().getRefreshReuseGrace()));
        if (current.isRevoked() && !raceWithinGrace) {
            if ("ROTATED".equals(current.getRevokedReason())) {
                int revoked = tokens.revokeFamily(current.getFamilyId(), "REUSE_DETECTED", now);
                audit.record(AuditEntry.builder()
                        .actor(current.getUserId(), null)
                        .action(AuditAction.REFRESH_TOKEN_REUSED)
                        .entity("AppUser", current.getUserId())
                        .after(AuditJson.of("family", current.getFamilyId(), "revoked", revoked, "ip", ip))
                        .build());
                log.warn("refresh-token-reuse userId={} family={} revoked={}", current.getUserId(),
                        current.getFamilyId(), revoked);
            }
            return Optional.empty();
        }
        if (current.isExpired(now)) return Optional.empty();

        AppUser user = users.findById(current.getUserId()).orElse(null);
        if (user == null || !user.isActive() || user.isLocked()) {
            tokens.revokeFamily(current.getFamilyId(), "ACCOUNT_SUSPENDED", now);
            return Optional.empty();
        }

        Issued next = issue(user, current.getFamilyId(), current.getFamilyStartedAt(), now, ip, userAgent);
        if (!raceWithinGrace) {
            RefreshToken successor = tokens.findByTokenHash(hash(next.rawToken())).orElseThrow();
            current.rotatedInto(successor.getId(), now);
            tokens.save(current);
        }
        return Optional.of(next);
    }

    /** Signing out ends this sign-in on this device. */
    @Transactional
    public void signOut(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        tokens.findByTokenHash(hash(rawToken)).ifPresent(t -> {
            int revoked = tokens.revokeFamily(t.getFamilyId(), "SIGNED_OUT", Instant.now());
            if (revoked > 0) {
                audit.record(AuditEntry.builder()
                        .actor(t.getUserId(), null)
                        .action(AuditAction.SIGNED_OUT)
                        .entity("AppUser", t.getUserId())
                        .build());
            }
        });
    }

    /** Ends every sign-in a user has, on every device: suspension or a new password. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeAllFor(Long userId, String reason) {
        tokens.revokeAllForUser(userId, reason, Instant.now());
    }

    /** Expired tokens have no further use; keep a month for investigation, then delete. */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    @Transactional
    public void purgeExpired() {
        int deleted = tokens.deleteExpiredBefore(Instant.now().minus(Duration.ofDays(30)));
        if (deleted > 0) log.info("Purged {} expired refresh token(s)", deleted);
    }

    private Issued issue(AppUser user, String familyId, Instant familyStartedAt, Instant now,
                         String ip, String userAgent) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Instant idleExpiry = now.plus(properties.getJwt().getRefreshTokenTtl());
        Instant absoluteExpiry = familyStartedAt.plus(properties.getJwt().getSessionMaxAge());
        Instant expiresAt = idleExpiry.isBefore(absoluteExpiry) ? idleExpiry : absoluteExpiry;

        tokens.save(new RefreshToken(user.getId(), hash(raw), familyId, familyStartedAt, now, expiresAt,
                truncate(ip, 45), truncate(userAgent, 255)));
        return new Issued(raw, user, Duration.between(now, expiresAt));
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
