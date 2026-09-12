package com.seastella.identity.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Binds {@code seastella.security.*} from application.yml. */
@ConfigurationProperties(prefix = "seastella.security")
public class SecurityProperties {

    private Jwt jwt = new Jwt();
    private Lockout lockout = new Lockout();

    public Jwt getJwt() { return jwt; }
    public void setJwt(Jwt jwt) { this.jwt = jwt; }
    public Lockout getLockout() { return lockout; }
    public void setLockout(Lockout lockout) { this.lockout = lockout; }

    public static class Jwt {
        private String issuer = "seastella-platform";
        /** Overridden from the environment in production; never committed. */
        private String secret = "dev-only-secret-change-me-in-production-0123456789abcdef";
        private Duration accessTokenTtl = Duration.ofMinutes(15);
        private Duration refreshTokenTtl = Duration.ofDays(7);

        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public Duration getAccessTokenTtl() { return accessTokenTtl; }
        public void setAccessTokenTtl(Duration t) { this.accessTokenTtl = t; }
        public Duration getRefreshTokenTtl() { return refreshTokenTtl; }
        public void setRefreshTokenTtl(Duration t) { this.refreshTokenTtl = t; }
    }

    public static class Lockout {
        private int maxFailedAttempts = 5;
        private Duration lockoutDuration = Duration.ofMinutes(15);

        public int getMaxFailedAttempts() { return maxFailedAttempts; }
        public void setMaxFailedAttempts(int n) { this.maxFailedAttempts = n; }
        public Duration getLockoutDuration() { return lockoutDuration; }
        public void setLockoutDuration(Duration d) { this.lockoutDuration = d; }
    }
}
