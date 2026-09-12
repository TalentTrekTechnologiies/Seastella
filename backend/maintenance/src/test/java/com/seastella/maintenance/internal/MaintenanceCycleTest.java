package com.seastella.maintenance.internal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** MNT-10: a completed service resets the cycle. */
class MaintenanceCycleTest {

    @Test
    void calendarRuleComputesNextDueFromLastService() {
        SpareMaintenanceRule rule = SpareMaintenanceRule.calendar(
                1L, 10L, 180, LocalDate.of(2026, 1, 1));

        assertThat(rule.getNextDueDate()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void completingAServiceRollsTheCalendarCycleForward() {
        SpareMaintenanceRule rule = SpareMaintenanceRule.calendar(
                1L, 10L, 180, LocalDate.of(2026, 1, 1));

        rule.completeService(LocalDate.of(2026, 7, 15), null);

        assertThat(rule.getLastServiceDate()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(rule.getNextDueDate()).isEqualTo(LocalDate.of(2027, 1, 11));
    }

    @Test
    void runningHourRuleAccumulatesFromTheServiceReading() {
        SpareMaintenanceRule rule = SpareMaintenanceRule.runningHours(
                1L, 10L, new BigDecimal("5000.00"), new BigDecimal("1200.00"));

        assertThat(rule.getNextDueHours()).isEqualByComparingTo("6200.00");

        rule.completeService(LocalDate.of(2026, 7, 15), new BigDecimal("6350.00"));

        assertThat(rule.getLastServiceHours()).isEqualByComparingTo("6350.00");
        assertThat(rule.getNextDueHours()).isEqualByComparingTo("11350.00");
    }
}
