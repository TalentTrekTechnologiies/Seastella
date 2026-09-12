package com.seastella.servicerequest.internal;

import com.seastella.core.api.seed.SeedContext;
import com.seastella.core.api.seed.SeedContributor;
import com.seastella.fleet.api.FleetDirectory;
import com.seastella.servicerequest.api.Priority;
import com.seastella.servicerequest.api.ResolutionType;
import com.seastella.servicerequest.api.ServiceRequestAction;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Seeds problem types and a set of Service Requests that between them occupy
 * <em>every</em> state of the workflow.
 *
 * <p>That coverage is the point. A dashboard cannot be judged against a dataset
 * where nothing is awaiting approval, no invoice is pending and no engineer is
 * on site: the queues would all render empty and look correct. Each request
 * below is placed in a specific state, with a transition history consistent
 * with how it got there, so every dashboard queue has something real in it.
 *
 * <p>Requests are placed directly into their state rather than walked through
 * the state machine, because several target states need an actor whose scope is
 * not established during seeding. The transition log is written alongside, so
 * the timeline a user sees is still coherent.
 */
@Component
public class ServiceRequestSeedContributor implements SeedContributor {

    private final ServiceRequestRepository requests;
    private final ServiceRequestTransitionLogRepository transitions;
    private final CompletionReportRepository completionReports;
    private final ProblemTypeRepository problemTypes;
    private final FleetDirectory fleet;
    private final JdbcTemplate jdbc;

    ServiceRequestSeedContributor(ServiceRequestRepository requests,
                                  ServiceRequestTransitionLogRepository transitions,
                                  CompletionReportRepository completionReports,
                                  ProblemTypeRepository problemTypes,
                                  FleetDirectory fleet,
                                  JdbcTemplate jdbc) {
        this.requests = requests;
        this.transitions = transitions;
        this.completionReports = completionReports;
        this.problemTypes = problemTypes;
        this.fleet = fleet;
        this.jdbc = jdbc;
    }

    @Override public int order() { return 40; }

    @Override public String name() { return "service requests (every workflow state)"; }

    @Override
    public void contribute(SeedContext ctx) {
        seedProblemTypes(ctx);

        Long acme = ctx.id("org.acme");
        Long kestrel = ctx.id("vessel.kestrel");
        Long brahma = ctx.id("vessel.brahmaputra");
        Long coral = ctx.id("vessel.coral");
        Long sable = ctx.id("vessel.sable");

        Long capKestrel = ctx.id("user.captain.kestrel");
        Long capBrahma = ctx.id("user.captain.brahmaputra");
        Long capCoral = ctx.id("user.captain.coral");
        Long capSable = ctx.id("user.captain.sable");
        Long sm1 = ctx.id("user.sm.one");
        Long sm2 = ctx.id("user.sm.two");
        Long coordinator = ctx.id("user.coordinator");
        Long engineer1 = ctx.id("user.engineer.one");
        Long engineer2 = ctx.id("user.engineer.two");

        int seq = 1;

        // --- Phase A states --------------------------------------------------
        seq = request(ctx, "sr.troubleshooting", seq, acme, kestrel, capKestrel, sm1,
                "14.1.2", "ECDIS No.1 display fan intermittent",
                "Display fan on ECDIS No.1 stops intermittently. Unit reaches 58 C after "
                        + "roughly four hours; fan restarts if the unit is power-cycled.",
                Priority.HIGH, ServiceRequestStatus.TROUBLESHOOTING, 2);

        seq = request(ctx, "sr.liveagent", seq, acme, brahma, capBrahma, sm1,
                "21.1", "GPS No.1 loses fix intermittently",
                "GPS No.1 drops position fix for 30-90 seconds at irregular intervals. "
                        + "Guided checks confirmed antenna continuity; issue persists.",
                Priority.CRITICAL, ServiceRequestStatus.LIVE_AGENT_ESCALATED, 3);

        seq = request(ctx, "sr.pending.one", seq, acme, kestrel, capKestrel, sm1,
                "13.1.1", "X-Band magnetron output degraded",
                "Weak returns beyond 8 NM. Magnetron has 4,180 running hours against a "
                        + "5,000 hour interval. Requesting replacement at next port.",
                Priority.HIGH, ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL, 5);

        seq = request(ctx, "sr.pending.two", seq, acme, coral, capCoral, sm2,
                "11.1", "VDR recording alarm on startup",
                "VDR raises a recording fault alarm on every startup. Alarm clears after "
                        + "approximately ten minutes but recurs on the next power cycle.",
                Priority.CRITICAL, ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL, 1);

        seq = request(ctx, "sr.clarification", seq, acme, coral, capCoral, sm2,
                "17.1", "Anemometer reading erratic in heavy weather",
                "Wind speed readings fluctuate by up to 20 knots in heavy weather.",
                Priority.LOW, ServiceRequestStatus.CLARIFICATION_REQUESTED, 6);

        seq = request(ctx, "sr.rejected", seq, acme, sable, capSable, sm2,
                "20.1", "ITU List IV replacement requested",
                "Current edition superseded; requesting replacement publication.",
                Priority.LOW, ServiceRequestStatus.REJECTED, 12);

        // --- Coordinator triage ---------------------------------------------
        seq = request(ctx, "sr.approved", seq, acme, brahma, capBrahma, sm1,
                "2.1", "VHF No.1 unable to transmit on Ch.16",
                "VHF No.1 receives normally but will not transmit on Channel 16. "
                        + "VHF No.2 is serviceable and in use.",
                Priority.CRITICAL, ServiceRequestStatus.OPERATIONALLY_APPROVED, 4);

        seq = request(ctx, "sr.closed.nocost", seq, acme, kestrel, capKestrel, sm1,
                "19.1", "BNWAS alarm not sounding on bridge",
                "BNWAS bridge alarm silent during watch test.",
                Priority.HIGH, ServiceRequestStatus.CLOSED_NO_COST, 9);

        // --- Phase B: the invoice gate --------------------------------------
        seq = request(ctx, "sr.invoice.raised", seq, acme, kestrel, capKestrel, sm1,
                "14.1.3", "ECDIS No.1 SSD read errors",
                "ECDIS No.1 logs SSD read errors during chart update. Chart rendering "
                        + "stutters when panning.",
                Priority.HIGH, ServiceRequestStatus.INVOICE_RAISED, 7);

        seq = request(ctx, "sr.invoice.queried", seq, acme, coral, capCoral, sm2,
                "12.1", "Gyro No.1 heading drift",
                "Gyro No.1 drifts approximately 1.5 degrees over a 12 hour watch cycle "
                        + "against GPS heading.",
                Priority.HIGH, ServiceRequestStatus.INVOICE_QUERIED, 11);

        seq = request(ctx, "sr.invoice.rejected", seq, acme, sable, capSable, sm2,
                "16.1", "Echo sounder no depth reading",
                "Echo sounder shows no depth reading alongside. Transducer suspected.",
                Priority.MEDIUM, ServiceRequestStatus.INVOICE_REJECTED, 14);

        seq = request(ctx, "sr.invoice.accepted", seq, acme, brahma, capBrahma, sm1,
                "13.2.1", "S-Band magnetron replacement",
                "S-Band magnetron at 4,890 running hours. Returns degraded beyond 12 NM.",
                Priority.HIGH, ServiceRequestStatus.INVOICE_ACCEPTED, 8);

        // --- Phase C: execution ---------------------------------------------
        seq = requestAssigned(ctx, "sr.assigned", seq, acme, kestrel, capKestrel, sm1,
                "13.1.4", "X-Band scanner unit fan seized",
                "Scanner unit fan seized. Scanner temperature alarm active above 15 RPM.",
                Priority.CRITICAL, ServiceRequestStatus.ENGINEER_ASSIGNED,
                coordinator, engineer1, 10);

        seq = requestAssigned(ctx, "sr.inprogress", seq, acme, coral, capCoral, sm2,
                "14.2.1", "ECDIS No.2 display fan failure",
                "ECDIS No.2 display fan not running. Unit shut down as a precaution; "
                        + "ECDIS No.1 in use.",
                Priority.HIGH, ServiceRequestStatus.IN_PROGRESS,
                coordinator, engineer1, 13);

        seq = requestAssigned(ctx, "sr.reported", seq, acme, brahma, capBrahma, sm1,
                "1.1", "AIS not transmitting own ship data",
                "AIS receives targets but own ship data is not transmitted. "
                        + "Confirmed by a nearby vessel.",
                Priority.CRITICAL, ServiceRequestStatus.COMPLETION_REPORTED,
                coordinator, engineer2, 16);

        seq = requestAssigned(ctx, "sr.completed", seq, acme, kestrel, capKestrel, sm1,
                "8.1.1", "EPIRB No.1 battery expiry replacement",
                "EPIRB No.1 battery reached expiry date. Replacement required before "
                        + "next class survey.",
                Priority.HIGH, ServiceRequestStatus.COMPLETED,
                coordinator, engineer2, 28);

        // A second completed request so trend data has more than one point.
        requestAssigned(ctx, "sr.completed.two", seq, acme, coral, capCoral, sm2,
                "13.2.2", "S-Band display fan replacement",
                "S-Band display fan noisy and running below rated speed.",
                Priority.MEDIUM, ServiceRequestStatus.COMPLETED,
                coordinator, engineer1, 45);
    }

    private void seedProblemTypes(SeedContext ctx) {
        if (!problemTypes.findAll().isEmpty()) {
            return;
        }
        record P(String cat, String code, String label, int order) {}
        List<P> defs = List.of(
                new P("ECDIS", "ECDIS_NO_GPS_INPUT", "No GPS input / position lost", 1),
                new P("ECDIS", "ECDIS_DISPLAY_FAULT", "Display blank or distorted", 2),
                new P("ECDIS", "ECDIS_FAN_FAILURE", "Cooling fan failure / overheating", 3),
                new P("ECDIS", "ECDIS_CHART_UPDATE", "Chart update failure", 4),
                new P("RADAR", "RADAR_NO_ECHO", "No echo / no target detection", 1),
                new P("RADAR", "RADAR_MAGNETRON_WEAK", "Weak returns / magnetron ageing", 2),
                new P("RADAR", "RADAR_SCANNER_FAULT", "Scanner not rotating", 3),
                new P("RADAR", "RADAR_FAN_FAILURE", "Cooling fan failure", 4),
                new P("GPS", "GPS_NO_FIX", "No position fix", 1),
                new P("GPS", "GPS_ANTENNA_FAULT", "Antenna or cabling fault", 2),
                new P("VHF", "VHF_NO_TRANSMIT", "Unable to transmit", 1),
                new P("AIS", "AIS_NOT_TRANSMITTING", "Not transmitting own ship data", 1),
                new P("GYRO", "GYRO_HEADING_DRIFT", "Heading drift / alignment error", 1),
                new P("VDR", "VDR_RECORDING_FAULT", "Recording failure / alarm", 1),
                new P("EPIRB", "EPIRB_BATTERY_EXPIRY", "Battery approaching expiry", 1),
                new P("ECHO_SOUNDER", "ECHO_NO_READING", "No depth reading", 1),
                new P("BNWAS", "BNWAS_ALARM_FAULT", "Alarm not sounding", 1),
                new P("ANEMOMETER", "ANEMOMETER_ERRATIC", "Erratic wind readings", 1),
                new P("ITU_PUB", "PUBLICATION_SUPERSEDED", "Publication superseded", 1));

        for (P d : defs) {
            fleet.equipmentCategoryIdByCode(d.cat()).ifPresent(catId ->
                    problemTypes.save(new ProblemType(catId, d.code(), d.label(), d.order())));
        }
    }

    private int request(SeedContext ctx, String handle, int seq, Long orgId, Long vesselId,
                        Long captainId, Long shipManagerId, String sparePath, String title,
                        String description, Priority priority, ServiceRequestStatus status,
                        int daysAgo) {
        return build(ctx, handle, seq, orgId, vesselId, captainId, shipManagerId, sparePath,
                title, description, priority, status, null, null, daysAgo);
    }

    private int requestAssigned(SeedContext ctx, String handle, int seq, Long orgId, Long vesselId,
                                Long captainId, Long shipManagerId, String sparePath, String title,
                                String description, Priority priority, ServiceRequestStatus status,
                                Long coordinatorId, Long engineerId, int daysAgo) {
        return build(ctx, handle, seq, orgId, vesselId, captainId, shipManagerId, sparePath,
                title, description, priority, status, coordinatorId, engineerId, daysAgo);
    }

    private int build(SeedContext ctx, String handle, int seq, Long orgId, Long vesselId,
                      Long captainId, Long shipManagerId, String sparePath, String title,
                      String description, Priority priority, ServiceRequestStatus status,
                      Long coordinatorId, Long engineerId, int daysAgo) {

        String vesselKey = vesselKeyFor(ctx, vesselId);
        String spareHandle = "spare." + vesselKey + "." + sparePath;
        if (!ctx.has(spareHandle)) {
            return seq;
        }
        Long spareId = ctx.id(spareHandle);

        String number = String.format("SR-ACME-%s-%04d",
                ctx.today().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")), seq);

        if (requests.existsByRequestNumber(number)) {
            return seq + 1;
        }

        ServiceRequest sr = new ServiceRequest(number, orgId, vesselId, spareId, captainId,
                title, description, priority);
        sr.seedStatus(status);
        sr.markSeed();

        Instant raisedAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS);

        if (reachedApproval(status)) {
            sr.seedApproval(shipManagerId, raisedAt.plus(1, ChronoUnit.DAYS));
        }
        if (engineerId != null && reachedAssignment(status)) {
            sr.seedAssignment(engineerId, coordinatorId, raisedAt.plus(3, ChronoUnit.DAYS));
        }
        if (status == ServiceRequestStatus.COMPLETED) {
            sr.seedClosure(coordinatorId, raisedAt.plus(6, ChronoUnit.DAYS),
                    ResolutionType.ENGINEER_VISIT);
        } else if (status == ServiceRequestStatus.CLOSED_NO_COST) {
            sr.seedClosure(shipManagerId, raisedAt.plus(2, ChronoUnit.DAYS),
                    ResolutionType.LIVE_CHAT);
        } else if (status == ServiceRequestStatus.REJECTED) {
            sr.seedClosure(shipManagerId, raisedAt.plus(1, ChronoUnit.DAYS), ResolutionType.NONE);
        }

        ServiceRequest saved = requests.saveAndFlush(sr);
        ctx.put(handle, saved.getId());

        // JPA auditing stamps created_at with "now", but a seeded request was
        // raised in the past and may already be closed. Left alone, closed_at
        // precedes created_at and every turnaround figure comes out negative.
        // Back-dating the audit column is the one place seeding legitimately
        // reaches past the entity.
        jdbc.update("update service_request set created_at = ?, updated_at = ? where id = ?",
                java.sql.Timestamp.from(raisedAt),
                java.sql.Timestamp.from(raisedAt),
                saved.getId());

        seedTransitionHistory(saved, status, captainId, shipManagerId, coordinatorId,
                engineerId, raisedAt);

        if (status == ServiceRequestStatus.COMPLETION_REPORTED
                || status == ServiceRequestStatus.COMPLETED) {
            seedCompletionReport(saved, engineerId, raisedAt, status);
        }
        return seq + 1;
    }

    /** Writes a transition log consistent with how the request reached its state. */
    private void seedTransitionHistory(ServiceRequest sr, ServiceRequestStatus target,
                                       Long captainId, Long shipManagerId, Long coordinatorId,
                                       Long engineerId, Instant raisedAt) {

        List<Object[]> steps = new ArrayList<>();
        steps.add(new Object[]{null, ServiceRequestStatus.REPORTED,
                ServiceRequestAction.RAISE, captainId, "CAPTAIN", 0});

        if (target == ServiceRequestStatus.REPORTED) {
            writeSteps(sr, steps, raisedAt);
            return;
        }
        steps.add(new Object[]{ServiceRequestStatus.REPORTED, ServiceRequestStatus.TROUBLESHOOTING,
                ServiceRequestAction.START_TROUBLESHOOTING, captainId, "CAPTAIN", 0});

        if (target == ServiceRequestStatus.TROUBLESHOOTING) {
            writeSteps(sr, steps, raisedAt);
            return;
        }
        if (target == ServiceRequestStatus.LIVE_AGENT_ESCALATED) {
            steps.add(new Object[]{ServiceRequestStatus.TROUBLESHOOTING,
                    ServiceRequestStatus.LIVE_AGENT_ESCALATED,
                    ServiceRequestAction.ESCALATE_TO_LIVE_AGENT, captainId, "CAPTAIN", 1});
            writeSteps(sr, steps, raisedAt);
            return;
        }

        steps.add(new Object[]{ServiceRequestStatus.TROUBLESHOOTING,
                ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL,
                ServiceRequestAction.SUBMIT_FOR_APPROVAL, captainId, "CAPTAIN", 1});

        switch (target) {
            case PENDING_OPERATIONAL_APPROVAL -> { }
            case CLARIFICATION_REQUESTED -> steps.add(new Object[]{
                    ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL,
                    ServiceRequestStatus.CLARIFICATION_REQUESTED,
                    ServiceRequestAction.REQUEST_CLARIFICATION, shipManagerId, "SHIP_MANAGER", 2});
            case REJECTED -> steps.add(new Object[]{
                    ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL, ServiceRequestStatus.REJECTED,
                    ServiceRequestAction.REJECT, shipManagerId, "SHIP_MANAGER", 2});
            default -> {
                steps.add(new Object[]{ServiceRequestStatus.PENDING_OPERATIONAL_APPROVAL,
                        ServiceRequestStatus.OPERATIONALLY_APPROVED,
                        ServiceRequestAction.APPROVE_OPERATIONAL, shipManagerId, "SHIP_MANAGER", 2});
                appendCoordinatorSteps(steps, target, shipManagerId, coordinatorId, engineerId);
            }
        }
        writeSteps(sr, steps, raisedAt);
    }

    private void appendCoordinatorSteps(List<Object[]> steps, ServiceRequestStatus target,
                                        Long shipManagerId, Long coordinatorId, Long engineerId) {
        if (target == ServiceRequestStatus.OPERATIONALLY_APPROVED) return;

        if (target == ServiceRequestStatus.CLOSED_NO_COST) {
            steps.add(new Object[]{ServiceRequestStatus.OPERATIONALLY_APPROVED,
                    ServiceRequestStatus.CLOSED_NO_COST, ServiceRequestAction.CLOSE_NO_COST,
                    coordinatorId, "SERVICE_COORDINATOR", 3});
            return;
        }

        steps.add(new Object[]{ServiceRequestStatus.OPERATIONALLY_APPROVED,
                ServiceRequestStatus.INVOICE_RAISED, ServiceRequestAction.RAISE_INVOICE,
                coordinatorId, "SERVICE_COORDINATOR", 3});

        switch (target) {
            case INVOICE_RAISED -> { }
            case INVOICE_QUERIED -> steps.add(new Object[]{ServiceRequestStatus.INVOICE_RAISED,
                    ServiceRequestStatus.INVOICE_QUERIED, ServiceRequestAction.QUERY_INVOICE,
                    shipManagerId, "SHIP_MANAGER", 4});
            case INVOICE_REJECTED -> steps.add(new Object[]{ServiceRequestStatus.INVOICE_RAISED,
                    ServiceRequestStatus.INVOICE_REJECTED, ServiceRequestAction.REJECT_INVOICE,
                    shipManagerId, "SHIP_MANAGER", 4});
            default -> {
                steps.add(new Object[]{ServiceRequestStatus.INVOICE_RAISED,
                        ServiceRequestStatus.INVOICE_ACCEPTED, ServiceRequestAction.ACCEPT_INVOICE,
                        shipManagerId, "SHIP_MANAGER", 4});
                if (target == ServiceRequestStatus.INVOICE_ACCEPTED) return;

                steps.add(new Object[]{ServiceRequestStatus.INVOICE_ACCEPTED,
                        ServiceRequestStatus.ENGINEER_ASSIGNED, ServiceRequestAction.ASSIGN_ENGINEER,
                        coordinatorId, "SERVICE_COORDINATOR", 5});
                if (target == ServiceRequestStatus.ENGINEER_ASSIGNED) return;

                steps.add(new Object[]{ServiceRequestStatus.ENGINEER_ASSIGNED,
                        ServiceRequestStatus.IN_PROGRESS, ServiceRequestAction.START_WORK,
                        engineerId, "SERVICE_ENGINEER", 5});
                if (target == ServiceRequestStatus.IN_PROGRESS) return;

                steps.add(new Object[]{ServiceRequestStatus.IN_PROGRESS,
                        ServiceRequestStatus.COMPLETION_REPORTED,
                        ServiceRequestAction.SUBMIT_COMPLETION, engineerId, "SERVICE_ENGINEER", 6});
                if (target == ServiceRequestStatus.COMPLETION_REPORTED) return;

                // Only the Coordinator closes the loop to the Ship Manager.
                steps.add(new Object[]{ServiceRequestStatus.COMPLETION_REPORTED,
                        ServiceRequestStatus.COMPLETED, ServiceRequestAction.COMPLETE,
                        coordinatorId, "SERVICE_COORDINATOR", 6});
            }
        }
    }

    private void writeSteps(ServiceRequest sr, List<Object[]> steps, Instant raisedAt) {
        for (Object[] s : steps) {
            transitions.save(new ServiceRequestTransitionLog(
                    sr.getId(), sr.getVesselId(),
                    (ServiceRequestStatus) s[0], (ServiceRequestStatus) s[1],
                    (ServiceRequestAction) s[2], (Long) s[3], (String) s[4], null,
                    raisedAt.plus((Integer) s[5], ChronoUnit.DAYS)));
        }
    }

    private void seedCompletionReport(ServiceRequest sr, Long engineerId, Instant raisedAt,
                                      ServiceRequestStatus status) {
        if (engineerId == null) return;

        CompletionReport report = new CompletionReport(
                sr.getId(), sr.getVesselId(), engineerId,
                "Attended vessel, isolated supply, replaced the defective unit and "
                        + "verified operation against the manufacturer's test procedure.",
                "Fault cleared; unit returned to service and tested satisfactory.",
                LocalDate.now().minusDays(2));
        report.setPartsUsed("1 x replacement unit, 1 x mounting kit");
        report.setFinalCost(new BigDecimal("1850.00"));
        report.setReportedAt(raisedAt.plus(6, ChronoUnit.DAYS));
        report.markSeed();

        if (status == ServiceRequestStatus.COMPLETED) {
            // Reconciled against an accepted invoice of 1,750 - a deliberate
            // 100.00 variance, so the Coordinator's variance flag has a case.
            report.reconcile(new BigDecimal("1750.00"),
                    "Service completed. Final cost 100.00 above the accepted invoice "
                            + "owing to an additional mounting kit.",
                    raisedAt.plus(6, ChronoUnit.DAYS));
        }
        completionReports.save(report);
    }

    private static boolean reachedApproval(ServiceRequestStatus s) {
        return switch (s) {
            case OPERATIONALLY_APPROVED, CLOSED_NO_COST, INVOICE_RAISED, INVOICE_QUERIED,
                 INVOICE_REJECTED, INVOICE_ACCEPTED, ENGINEER_ASSIGNED, IN_PROGRESS,
                 COMPLETION_REPORTED, COMPLETED -> true;
            default -> false;
        };
    }

    private static boolean reachedAssignment(ServiceRequestStatus s) {
        return switch (s) {
            case ENGINEER_ASSIGNED, IN_PROGRESS, COMPLETION_REPORTED, COMPLETED -> true;
            default -> false;
        };
    }

    private static String vesselKeyFor(SeedContext ctx, Long vesselId) {
        for (Map.Entry<String, Long> e : ctx.all().entrySet()) {
            if (e.getKey().startsWith("vessel.") && e.getValue().equals(vesselId)) {
                return e.getKey().substring("vessel.".length());
            }
        }
        return "";
    }
}
