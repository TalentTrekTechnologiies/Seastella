package com.seastella.fleet.api;

import java.time.LocalDate;

/**
 * The spare's own record of when it was last serviced (SoW s9.3, SRQ-19).
 *
 * <p>The maintenance module keeps the <em>cycle</em> - when the next service
 * falls due. This is the other half: what was actually done to the item, and
 * when - which is what a surveyor asks for, what the equipment report prints,
 * and what an auditor reads down.
 */
public interface SpareServiceHistory {

    /**
     * Writes a completed service request into the spare's history.
     *
     * <p>Idempotent on the request: replaying a completion does not produce a
     * second row. A date older than the newest already recorded is kept in the
     * history but does not move the spare's last service date backwards.
     */
    void recordCompletedService(CompletedService service);

    /**
     * What the engineer reported, carried across so the history says what was
     * done rather than only when.
     */
    record CompletedService(Long spareId, Long serviceRequestId, String requestNumber, LocalDate serviceDate,
                            String workPerformed, String partsUsed, String performedBy) {}
}
