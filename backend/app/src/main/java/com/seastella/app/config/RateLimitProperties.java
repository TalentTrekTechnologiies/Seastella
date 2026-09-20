package com.seastella.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Request limits (SEC-23). Binds {@code seastella.security.rate-limit.*}.
 *
 * <p>Two limits, both per minute. {@code sensitive} covers the endpoints worth
 * attacking with a script - signing in, and the links that set a password -
 * counted per client address. {@code writes} is a blanket ceiling on anything
 * that changes data, counted per signed-in user, so one account cannot hammer
 * the platform. Reads are not limited: dashboards and the chat poll.
 */
@ConfigurationProperties(prefix = "seastella.security.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private int sensitivePerMinute = 12;
    private int writesPerMinute = 120;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getSensitivePerMinute() { return sensitivePerMinute; }
    public void setSensitivePerMinute(int n) { this.sensitivePerMinute = n; }
    public int getWritesPerMinute() { return writesPerMinute; }
    public void setWritesPerMinute(int n) { this.writesPerMinute = n; }
}
