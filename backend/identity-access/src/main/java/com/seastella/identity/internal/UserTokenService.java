package com.seastella.identity.internal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Invitation and password-reset links.
 *
 * <p>256 random bits, delivered by email; only the hash is stored, so neither
 * a database copy nor an administrator can use a link. Issuing a new link of a
 * purpose revokes the user's earlier ones, so only the latest email works.
 */
@Service
public class UserTokenService {

    static final Duration INVITATION_TTL = Duration.ofHours(72);
    static final Duration RESET_TTL = Duration.ofHours(1);
    static final int SELF_SERVICE_LIMIT = 3;
    static final Duration SELF_SERVICE_WINDOW = Duration.ofHours(1);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserTokenRepository tokens;

    UserTokenService(UserTokenRepository tokens) {
        this.tokens = tokens;
    }

    public record Issued(String rawToken, Instant expiresAt) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Issued issue(Long userId, UserToken.Purpose purpose, Long createdByUserId) {
        Instant now = Instant.now();
        revokeOpen(userId, purpose, now);

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = now.plus(purpose == UserToken.Purpose.INVITATION ? INVITATION_TTL : RESET_TTL);
        tokens.save(new UserToken(userId, purpose, RefreshTokenService.hash(raw), expiresAt, createdByUserId));
        return new Issued(raw, expiresAt);
    }

    /** A link that can still be used for this purpose; empty for unknown, used, revoked or expired. */
    @Transactional(readOnly = true)
    public Optional<UserToken> usable(String rawToken, UserToken.Purpose purpose) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 128) return Optional.empty();
        Instant now = Instant.now();
        return tokens.findByTokenHash(RefreshTokenService.hash(rawToken))
                .filter(t -> t.getPurpose() == purpose && t.isUsable(now));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(UserToken token) {
        token.use(Instant.now());
        tokens.save(token);
    }

    /**
     * Whether another "forgot password" email may go to this account now. Caps
     * self-service requests per account, so the public endpoint cannot be used
     * to flood someone's inbox. Administrator-sent links are not counted.
     */
    @Transactional(readOnly = true)
    public boolean selfServiceResetAllowed(Long userId) {
        return tokens.countByUserIdAndPurposeAndCreatedByUserIdIsNullAndCreatedAtAfter(
                userId, UserToken.Purpose.PASSWORD_RESET, Instant.now().minus(SELF_SERVICE_WINDOW)) < SELF_SERVICE_LIMIT;
    }

    /** Invited at some point and never accepted - the account still has no password of its owner's choosing. */
    @Transactional(readOnly = true)
    public boolean neverAccepted(Long userId) {
        return tokens.existsByUserIdAndPurpose(userId, UserToken.Purpose.INVITATION)
                && !tokens.existsByUserIdAndPurposeAndUsedAtIsNotNull(userId, UserToken.Purpose.INVITATION);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeOpen(Long userId, UserToken.Purpose purpose) {
        revokeOpen(userId, purpose, Instant.now());
    }

    private void revokeOpen(Long userId, UserToken.Purpose purpose, Instant now) {
        for (UserToken t : tokens.findByUserIdAndPurpose(userId, purpose)) {
            t.revoke(now);
            tokens.save(t);
        }
    }
}
