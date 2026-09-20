package com.seastella.fleet.api;

import java.time.LocalDate;

/**
 * The spare's own record of when it was last serviced (SoW s9.3, SRQ-19).
 *
 * <p>The maintenance module keeps the <em>cycle</em> - when the next service
 * falls due. This is the other half: the spare itself carries the date of its
 * last annual service, which is what a surveyor asks for and what the equipment
 * report prints.
 */
public interface SpareServiceHistory {

    /**
     * Records a completed service against the spare. A date older than the one
     * already recorded is kept for the trail but does not move the spare's last
     * service date backwards.
     */
    void recordCompletedService(Long spareId, LocalDate serviceDate, String requestNumber);
}
