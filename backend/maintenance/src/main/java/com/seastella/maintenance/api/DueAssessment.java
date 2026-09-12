package com.seastella.maintenance.api;

import java.time.LocalDate;

/**
 * The engine's verdict for one spare. Carries both the status and the numbers
 * behind it, so the UI can render "Urgent - 4 days" without recomputing
 * anything.
 *
 * @param status        the colour status
 * @param daysRemaining negative when overdue; null when not tracked
 * @param nextDueDate   null when not tracked
 * @param basis         which rule decided it
 */
public record DueAssessment(
        DueStatus status,
        Integer daysRemaining,
        LocalDate nextDueDate,
        Basis basis) {

    public enum Basis { CALENDAR, RUNNING_HOURS, NONE }

    public static DueAssessment notTracked() {
        return new DueAssessment(DueStatus.NOT_TRACKED, null, null, Basis.NONE);
    }
}
