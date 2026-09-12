package com.seastella.identity.internal;

import com.seastella.identity.api.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/** Issues and verifies access tokens (SEC-02). */
@Service
public class JwtService {

    private final SecurityProperties properties;
    private final SecretKey key;

    JwtService(SecurityProperties properties) {
        this.properties = properties;
        byte[] secret = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "seastella.security.jwt.secret must be at least 32 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    public String issueAccessToken(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(properties.getJwt().getIssuer())
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("org", user.getOrganizationId())
                .claim("name", user.getFullName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.getJwt().getAccessTokenTtl())))
                .signWith(key)
                .compact();
    }

    /** Empty when the token is absent, malformed, expired or wrongly signed. */
    public Optional<ParsedToken> parse(String token) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.getJwt().getIssuer())
                    .build()
                    .parseSignedClaims(token);

            Claims c = jws.getPayload();
            Long orgId = c.get("org", Number.class) == null
                    ? null : c.get("org", Number.class).longValue();

            return Optional.of(new ParsedToken(
                    Long.valueOf(c.getSubject()),
                    c.get("email", String.class),
                    Role.valueOf(c.get("role", String.class)),
                    orgId,
                    c.get("name", String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            // Deliberately opaque: the caller learns only that the token is unusable.
            return Optional.empty();
        }
    }

    public long accessTokenTtlSeconds() {
        return properties.getJwt().getAccessTokenTtl().toSeconds();
    }

    public record ParsedToken(Long userId, String email, Role role, Long organizationId, String fullName) {}
}
