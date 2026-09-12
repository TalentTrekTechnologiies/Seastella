package com.seastella.app.seed;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Decides whether the dataset is already present.
 *
 * <p>Checked against the organization table rather than a flag, so a partially
 * seeded database - one where a contributor failed - still looks unseeded and
 * is rebuilt rather than left half-populated.
 */
@Component
public class SeedGuard {

    private final JdbcTemplate jdbc;

    SeedGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean alreadySeeded() {
        Integer orgs = jdbc.queryForObject(
                "select count(*) from organization where seed_marker = 'SEED'", Integer.class);
        Integer requests = jdbc.queryForObject(
                "select count(*) from service_request where seed_marker = 'SEED'", Integer.class);
        return orgs != null && orgs > 0 && requests != null && requests > 0;
    }
}
