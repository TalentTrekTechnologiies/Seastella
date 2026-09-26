package com.seastella.core.api.seed;

/**
 * One module's contribution to the demo dataset.
 *
 * <p>Implementations live inside the module that owns the tables, so each uses
 * its own repositories and no module seeds another's data.
 */
public interface SeedContributor {

    /** Lower runs first. Fleet 10, identity 20, maintenance 30, requests 40, invoices 50. */
    int order();

    /** Human-readable name for the startup log. */
    String name();

    void contribute(SeedContext context);
}
