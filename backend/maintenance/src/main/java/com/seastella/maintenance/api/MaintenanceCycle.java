package com.seastella.maintenance.api;

import java.time.LocalDate;

/**
 * Resets a spare's maintenance cycle.
 *
 * <p>SoW s6.3 and acceptance criterion s18: "Completed services update the
 * Spare's service history and invoice record, and recalculate the
 * next-service-due date." The service-request module calls this when the
 * Coordinator confirms completion; it cannot listen for the event itself,
 * because service-request sits above maintenance.
 */
public interface MaintenanceCycle {

    /**
     * Restarts every active rule on the spare from the service date: calendar
     * rules from the date, running-hour rules from the meter's current reading.
     */
    void serviceCompleted(Long spareId, LocalDate serviceDate);
}
