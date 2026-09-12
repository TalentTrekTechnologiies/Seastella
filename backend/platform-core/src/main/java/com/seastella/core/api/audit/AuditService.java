package com.seastella.core.api.audit;

/**
 * Records audit entries.
 *
 * <p>Implementations must write in the <em>caller's</em> transaction (AUD-04),
 * never a new one. If the business change rolls back, its audit row must roll
 * back with it: an audit trail that records attempts as though they were facts
 * is worse than none, because it is trusted.
 */
public interface AuditService {

    void record(AuditEntry entry);

    /** Convenience for the common create/update shape. */
    void record(String action, String entityType, Long entityId, String before, String after);
}
