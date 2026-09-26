package com.seastella.core.api.seed;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Carries generated ids between seed contributors.
 *
 * <p>Contributors run in separate modules and must not import one another's
 * entities, so they exchange named handles - {@code "vessel.kestrel"},
 * {@code "user.captain.kestrel"} - resolving to database ids. That keeps the
 * seed data relationally consistent without opening a boundary.
 */
public class SeedContext {

    private final Map<String, Long> ids = new LinkedHashMap<>();
    private final LocalDate today;

    public SeedContext(LocalDate today) {
        this.today = today;
    }

    public LocalDate today() { return today; }

    public void put(String handle, Long id) {
        ids.put(handle, id);
    }

    public Long id(String handle) {
        Long id = ids.get(handle);
        if (id == null) {
            throw new IllegalStateException(
                    "Seed handle not found: " + handle + ". Check contributor ordering.");
        }
        return id;
    }

    public boolean has(String handle) {
        return ids.containsKey(handle);
    }

    public Map<String, Long> all() {
        return Map.copyOf(ids);
    }
}
