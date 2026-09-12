package com.seastella.maintenance.api;

import java.time.LocalDate;

/**
 * <b>The single source of truth for maintenance colour status</b> (MNT-07).
 *
 * <p>The master brief is blunt about why this is one component: "Do not
 * calculate different statuses independently in different frontend components."
 * Dashboards, spare screens, alerts and reports all call this. The frontend
 * badge <em>receives</em> a status and never derives one - a {@code
 * daysRemaining} prop with a comparison in a React component is exactly the bug
 * this interface exists to prevent.
 */
public interface MaintenanceStatusEngine {

    /** Assess one spare against today. */
    DueAssessment assess(Long spareId);

    /** Assess against an explicit date - used by tests and by back-dated reports. */
    DueAssessment assess(Long spareId, LocalDate asOf);

    /**
     * Classify a raw day count. Exposed so callers holding a due date already
     * (the import preview, for instance) need not re-read the rule.
     *
     * @param daysRemaining negative when past due
     */
    DueStatus classify(int daysRemaining, Long organizationId);
}
