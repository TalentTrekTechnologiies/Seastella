package com.seastella.identity.internal;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The refresh cookie, built one way everywhere a session starts or ends:
 * sign-in, accepting an invitation, resetting or changing a password, sign-out.
 */
@Component
class SessionCookies {

    static final String NAME = "seastella_refresh";

    private final SecurityProperties properties;

    SessionCookies(SecurityProperties properties) {
        this.properties = properties;
    }

    String issue(RefreshTokenService.Issued refresh) {
        return build(refresh.rawToken(), refresh.cookieMaxAge());
    }

    String clear() {
        return build("", Duration.ZERO);
    }

    private String build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(properties.getJwt().isRefreshCookieSecure())
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
