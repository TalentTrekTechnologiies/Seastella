package com.seastella.app.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls demo-data loading. Disabled in the prod profile (NFR-11).
 *
 * <p>Every seeded row carries {@code seed_marker = 'SEED'} so demo data is
 * distinguishable from real client data at the database level, not just by
 * convention - a distinction that matters the first time a pilot database is
 * promoted.
 */
@ConfigurationProperties(prefix = "seastella.seed")
public class SeedProperties {

    private boolean enabled = false;
    private String marker = "SEED";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getMarker() { return marker; }
    public void setMarker(String marker) { this.marker = marker; }
}
