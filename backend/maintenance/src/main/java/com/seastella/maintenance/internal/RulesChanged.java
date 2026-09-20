package com.seastella.maintenance.internal;

import java.util.Set;

/**
 * Internal signal: these spares' rules moved, so their colour status must be
 * re-checked once the change has committed. Not a domain event - nothing
 * outside maintenance needs it; the resulting status change is the event.
 */
record RulesChanged(Set<Long> spareIds) {}
