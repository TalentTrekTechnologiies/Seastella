package com.seastella.reporting.api;

import com.seastella.reporting.api.DashboardCommon.Kpi;
import com.seastella.reporting.api.DashboardCommon.Meta;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A job workspace, not an analytics dashboard (SoW s5, master brief s7.6).
 *
 * <p>What is <b>absent</b> is as deliberate as what is present: no other
 * engineer's jobs, no unassigned vessel, no organization-wide figures, no
 * activity feed, no administration, and <b>no invoice amount</b>. The engineer
 * receives the technical context s6.2 requires - the original report and the
 * troubleshooting log - and the fact that an invoice was accepted, but never
 * its value.
 */
public record ServiceEngineerDashboard(
        Meta meta,
        List<Kpi> kpis,
        List<Job> todaysSchedule,
        List<Job> upcoming,
        List<Job> inProgress,
        List<Job> awaitingMyReport,
        List<CompletedJob> recentlyCompleted) {

    /**
     * One assigned job. {@code invoiceAccepted} is a boolean, never an amount:
     * the engineer needs to know the job is authorised, not what it costs.
     */
    public record Job(
            Long serviceRequestId, String requestNumber,
            Long vesselId, String vesselName, String imoNumber,
            Long spareId, String spareName, String sparePath, String categoryCode,
            String make, String model, String serialNumber,
            String title, String problemDescription, String priority,
            String status, String statusLabel,
            boolean invoiceAccepted,
            Instant assignedAt, LocalDate scheduledFor, Integer ageDays) {}

    public record CompletedJob(
            Long serviceRequestId, String requestNumber, String vesselName,
            String spareName, String workPerformed, String outcome,
            LocalDate serviceDate, Instant reportedAt) {}
}
