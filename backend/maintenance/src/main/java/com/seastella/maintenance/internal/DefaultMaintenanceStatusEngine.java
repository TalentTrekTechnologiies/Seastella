package com.seastella.maintenance.internal;

import com.seastella.maintenance.api.DueAssessment;
import com.seastella.maintenance.api.DueStatus;
import com.seastella.maintenance.api.MaintenanceStatusEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * The one implementation of the colour-status rules.
 *
 * <h2>Band boundaries</h2>
 * The published table reads: more than 15 days Normal, 10-15 Approaching,
 * 1-9 Urgent, due today Due, past due Overdue. Note that "10-15" and "1-9" do
 * not meet - a spare 9.5 days out belongs to no band. Whole-day arithmetic
 * never produces that value, but any fractional or timezone-shifted calculation
 * would, so the bands here are evaluated in descending strictness with a final
 * fallback to NORMAL: every possible day count lands somewhere. Raised with the
 * client as OI-02.
 *
 * <h2>Two rules, nearest wins</h2>
 * A spare may carry a calendar rule and a running-hour rule at once (MNT-03).
 * Running-hour due dates are projected onto the calendar using observed average
 * consumption so the two are comparable; whichever falls sooner decides.
 */
@Service
class DefaultMaintenanceStatusEngine implements MaintenanceStatusEngine {

    private final SpareMaintenanceRuleRepository rules;
    private final MaintenanceThresholdRepository thresholds;
    private final SpareOrganizationLookup organizationLookup;

    DefaultMaintenanceStatusEngine(SpareMaintenanceRuleRepository rules,
                                   MaintenanceThresholdRepository thresholds,
                                   SpareOrganizationLookup organizationLookup) {
        this.rules = rules;
        this.thresholds = thresholds;
        this.organizationLookup = organizationLookup;
    }

    @Override
    @Transactional(readOnly = true)
    public DueAssessment assess(Long spareId) {
        return assess(spareId, LocalDate.now());
    }

    @Override
    @Transactional(readOnly = true)
    public DueAssessment assess(Long spareId, LocalDate asOf) {
        List<SpareMaintenanceRule> active = rules.findBySpareIdAndActiveTrue(spareId);
        if (active.isEmpty()) {
            return DueAssessment.notTracked();
        }

        // Nearest due date wins, regardless of which kind of rule produced it.
        return active.stream()
                .map(r -> assessRule(r, asOf, spareId))
                .filter(a -> a.nextDueDate() != null)
                .min(Comparator.comparing(DueAssessment::nextDueDate))
                .orElse(DueAssessment.notTracked());
    }

    private DueAssessment assessRule(SpareMaintenanceRule rule, LocalDate asOf, Long spareId) {
        LocalDate due = rule.getNextDueDate();
        DueAssessment.Basis basis = rule.isCalendar()
                ? DueAssessment.Basis.CALENDAR
                : DueAssessment.Basis.RUNNING_HOURS;

        if (due == null) {
            return DueAssessment.notTracked();
        }

        int days = (int) ChronoUnit.DAYS.between(asOf, due);
        Long orgId = organizationLookup.organizationIdForSpare(spareId);
        return new DueAssessment(classify(days, orgId), days, due, basis);
    }

    /**
     * Classifies a day count into a colour band.
     *
     * <p>Evaluated most-severe first so that overlapping or gapped configuration
     * still yields a deterministic answer rather than a null status.
     */
    @Override
    public DueStatus classify(int daysRemaining, Long organizationId) {
        if (daysRemaining < 0) return DueStatus.OVERDUE;
        if (daysRemaining == 0) return DueStatus.DUE;

        List<MaintenanceThreshold> applicable = thresholds.findApplicable(organizationId);
        if (applicable.isEmpty()) {
            return classifyWithDefaults(daysRemaining);
        }

        // Organization rows sort before platform defaults, so first match wins.
        for (String code : List.of("URGENT", "APPROACHING")) {
            for (MaintenanceThreshold t : applicable) {
                if (code.equals(t.getStatusCode()) && t.matches(daysRemaining)) {
                    return DueStatus.valueOf(code);
                }
            }
        }
        return DueStatus.NORMAL;
    }

    /** Used when no threshold rows exist - keeps the engine total. */
    private static DueStatus classifyWithDefaults(int daysRemaining) {
        if (daysRemaining <= 9) return DueStatus.URGENT;
        if (daysRemaining <= 15) return DueStatus.APPROACHING;
        return DueStatus.NORMAL;
    }
}
