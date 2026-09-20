package com.seastella.reporting.internal;

import com.seastella.core.api.error.ForbiddenException;
import com.seastella.fleet.api.DocumentDirectory;
import com.seastella.fleet.api.FleetMetrics;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.UserDirectory;
import com.seastella.invoice.api.InvoiceMetrics;
import com.seastella.invoice.api.InvoiceStatus;
import com.seastella.maintenance.api.MaintenanceMetrics;
import com.seastella.reporting.api.ReportCatalogue;
import com.seastella.reporting.api.ReportTable;
import com.seastella.servicerequest.api.ServiceRequestMetrics;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import com.seastella.troubleshooting.api.TroubleshootingHistory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The SoW s7 reports (RPT-01 to RPT-07, RPT-09).
 *
 * <p>Every report is built from the caller's own scope: the vessel set the
 * platform resolved for them, never a vessel id they asked for. A Ship Manager
 * running the fleet summary gets their vessels, a Technical Head the
 * organization's, and the Platform Admin everything - the same code, the same
 * report, different scope.
 *
 * <p>Cost is not merely hidden on screen: the invoice report is not offered to
 * the Captain or the Engineer at all (SoW s12), and asking for it by key is
 * refused rather than returned empty.
 */
@Service
class ReportService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final int MAX_ROWS = 5_000;

    private final DashboardSupport support;
    private final FleetMetrics fleet;
    private final MaintenanceMetrics maintenance;
    private final ServiceRequestMetrics requests;
    private final InvoiceMetrics invoices;
    private final DocumentDirectory documents;
    private final TroubleshootingHistory troubleshooting;
    private final UserDirectory users;

    ReportService(DashboardSupport support, FleetMetrics fleet, MaintenanceMetrics maintenance,
                  ServiceRequestMetrics requests, InvoiceMetrics invoices, DocumentDirectory documents,
                  TroubleshootingHistory troubleshooting, UserDirectory users) {
        this.support = support;
        this.fleet = fleet;
        this.maintenance = maintenance;
        this.requests = requests;
        this.invoices = invoices;
        this.documents = documents;
        this.troubleshooting = troubleshooting;
        this.users = users;
    }

    record Available(String key, String title, String description) {}

    List<Available> available() {
        AccessScope scope = support.scope();
        return List.of(ReportCatalogue.values()).stream()
                .filter(r -> r.availableTo(scope.role()))
                .map(r -> new Available(r.key(), r.title(), r.description()))
                .toList();
    }

    @Transactional(readOnly = true)
    ReportTable build(ReportCatalogue report) {
        AccessScope scope = support.scope();
        if (!report.availableTo(scope.role())) {
            throw ForbiddenException.ofAction("run the " + report.title().toLowerCase(Locale.ENGLISH) + " report");
        }
        Set<Long> vesselIds = scope.vesselIds();
        return switch (report) {
            case VESSEL_SPARES -> vesselSpares(scope, vesselIds);
            case SERVICE_DUE -> serviceDue(scope, vesselIds);
            case CERTIFICATES -> certificates(scope, vesselIds);
            case TROUBLESHOOTING -> troubleshooting(scope, vesselIds);
            case INVOICES -> invoices(scope, vesselIds);
            case FLEET_SUMMARY -> fleetSummary(scope, vesselIds);
            case PARTS_INVENTORY -> partsInventory(scope, vesselIds);
        };
    }

    // ------------------------------------------------------------- the reports

    private ReportTable vesselSpares(AccessScope scope, Set<Long> vesselIds) {
        List<List<String>> rows = new ArrayList<>();
        for (Long vesselId : vesselIds) {
            for (FleetMetrics.SpareNode spare : fleet.spareTree(vesselId)) {
                rows.add(List.of(
                        value(spare.vesselName()), value(spare.path()), value(spare.name()),
                        value(spare.categoryName()), value(spare.make()), value(spare.model()),
                        value(spare.serialNumber()),
                        spare.tracksRunningHours() ? value(spare.runningHours()) : "—",
                        spare.criticality() == null ? "—" : spare.criticality().name(),
                        spare.status() == null ? "—" : spare.status().name().replace('_', ' ')));
                if (rows.size() >= MAX_ROWS) break;
            }
        }
        return table(ReportCatalogue.VESSEL_SPARES, scope,
                List.of(ReportTable.text("Vessel"), ReportTable.text("VMP ref"), ReportTable.text("Spare"),
                        ReportTable.text("Category"), ReportTable.text("Make"), ReportTable.text("Model"),
                        ReportTable.text("Serial number"), ReportTable.number("Running hours"),
                        ReportTable.text("Criticality"), ReportTable.text("Status")),
                rows, List.of(new ReportTable.Total("Spares", String.valueOf(rows.size()))));
    }

    private ReportTable serviceDue(AccessScope scope, Set<Long> vesselIds) {
        List<MaintenanceMetrics.DueItem> due = new ArrayList<>(maintenance.dueSoon(vesselIds, 3_650, MAX_ROWS));
        due.addAll(maintenance.overdue(vesselIds, MAX_ROWS));
        due.sort(Comparator.comparing(d -> d.daysRemaining() == null ? Integer.MAX_VALUE : d.daysRemaining()));

        List<List<String>> rows = due.stream().<List<String>>map(d -> List.of(
                value(d.vesselName()), value(d.sparePath()), value(d.spareName()), value(d.categoryCode()),
                d.nextDueDate() == null ? "—" : DATE.format(d.nextDueDate()),
                d.daysRemaining() == null ? "—" : String.valueOf(d.daysRemaining()),
                d.status() == null ? "—" : d.status().label(),
                value(d.basis()))).toList();

        long overdue = due.stream().filter(d -> d.daysRemaining() != null && d.daysRemaining() < 0).count();
        return table(ReportCatalogue.SERVICE_DUE, scope,
                List.of(ReportTable.text("Vessel"), ReportTable.text("VMP ref"), ReportTable.text("Spare"),
                        ReportTable.text("Category"), ReportTable.text("Next due"), ReportTable.number("Days left"),
                        ReportTable.text("Status"), ReportTable.text("Basis")),
                rows, List.of(new ReportTable.Total("Tracked spares", String.valueOf(rows.size())),
                        new ReportTable.Total("Overdue", String.valueOf(overdue))));
    }

    private ReportTable certificates(AccessScope scope, Set<Long> vesselIds) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<DocumentDirectory.CertificateRef> certificates = documents.certificatesForVessels(List.copyOf(vesselIds));

        List<List<String>> rows = certificates.stream().<List<String>>map(c -> {
            long days = ChronoUnit.DAYS.between(today, c.expiryDate());
            return List.of(value(c.vesselName()), value(c.attachedTo()), value(c.title()),
                    value(c.certificateNumber()), value(c.issuingAuthority()),
                    c.issuedDate() == null ? "—" : DATE.format(c.issuedDate()),
                    DATE.format(c.expiryDate()), String.valueOf(days),
                    days < 0 ? "Expired" : days <= 30 ? "Expiring" : "Valid");
        }).toList();

        long expired = certificates.stream().filter(c -> c.expiryDate().isBefore(today)).count();
        long soon = certificates.stream()
                .filter(c -> !c.expiryDate().isBefore(today) && ChronoUnit.DAYS.between(today, c.expiryDate()) <= 30)
                .count();
        return table(ReportCatalogue.CERTIFICATES, scope,
                List.of(ReportTable.text("Vessel"), ReportTable.text("Attached to"), ReportTable.text("Certificate"),
                        ReportTable.text("Number"), ReportTable.text("Issued by"), ReportTable.text("Issued"),
                        ReportTable.text("Expires"), ReportTable.number("Days left"), ReportTable.text("State")),
                rows, List.of(new ReportTable.Total("Certificates", String.valueOf(rows.size())),
                        new ReportTable.Total("Expired", String.valueOf(expired)),
                        new ReportTable.Total("Expiring within 30 days", String.valueOf(soon))));
    }

    private ReportTable troubleshooting(AccessScope scope, Set<Long> vesselIds) {
        List<TroubleshootingHistory.SessionSummary> sessions = troubleshooting.sessions(vesselIds, MAX_ROWS);

        List<List<String>> rows = sessions.stream().<List<String>>map(s -> List.of(
                value(s.requestNumber()), value(s.vesselName()), value(s.spareName()), value(s.problemType()),
                s.flowName() + " v" + s.flowVersion() + (s.sampleContent() ? " (sample)" : ""),
                String.valueOf(s.answerCount()),
                s.outcome() == null ? "In progress" : s.outcome().label(),
                value(s.rootCauseNote()), value(s.temporaryFixNote()), value(s.runBy()),
                s.startedAt() == null ? "—" : DATE.format(s.startedAt().atZone(ZoneOffset.UTC)))).toList();

        long resolved = sessions.stream().filter(s -> s.outcome() != null
                && s.outcome() == com.seastella.troubleshooting.api.TroubleshootingOutcome.RESOLVED).count();
        return table(ReportCatalogue.TROUBLESHOOTING, scope,
                List.of(ReportTable.text("Request"), ReportTable.text("Vessel"), ReportTable.text("Spare"),
                        ReportTable.text("Problem"), ReportTable.text("Checks"), ReportTable.number("Answers"),
                        ReportTable.text("Outcome"), ReportTable.text("Likely cause"),
                        ReportTable.text("Temporary fix"), ReportTable.text("Run by"), ReportTable.text("Started")),
                rows, List.of(new ReportTable.Total("Sessions", String.valueOf(rows.size())),
                        new ReportTable.Total("Resolved on board", String.valueOf(resolved))));
    }

    private ReportTable invoices(AccessScope scope, Set<Long> vesselIds) {
        List<InvoiceMetrics.InvoiceSummary> found = invoices.forVessels(vesselIds, MAX_ROWS);

        List<List<String>> rows = found.stream().<List<String>>map(i -> List.of(
                value(i.invoiceNumber()), value(i.requestNumber()), value(i.vesselName()),
                value(i.statusLabel()), money(i.amount(), i.currency()), value(i.raisedByName()),
                i.raisedAt() == null ? "—" : DATE.format(i.raisedAt().atZone(ZoneOffset.UTC)),
                value(i.decidedByName()),
                i.decidedAt() == null ? "—" : DATE.format(i.decidedAt().atZone(ZoneOffset.UTC)))).toList();

        BigDecimal accepted = found.stream().filter(i -> i.status() == InvoiceStatus.ACCEPTED)
                .map(InvoiceMetrics.InvoiceSummary::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal raised = found.stream().filter(i -> i.status() == InvoiceStatus.RAISED)
                .map(InvoiceMetrics.InvoiceSummary::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String currency = found.isEmpty() ? "" : value(found.get(0).currency());
        return table(ReportCatalogue.INVOICES, scope,
                List.of(ReportTable.text("Invoice"), ReportTable.text("Request"), ReportTable.text("Vessel"),
                        ReportTable.text("Status"), ReportTable.number("Amount"), ReportTable.text("Raised by"),
                        ReportTable.text("Raised"), ReportTable.text("Decided by"), ReportTable.text("Decided")),
                rows, List.of(new ReportTable.Total("Invoices", String.valueOf(rows.size())),
                        new ReportTable.Total("Accepted", accepted + " " + currency),
                        new ReportTable.Total("Awaiting a decision", raised + " " + currency)));
    }

    private ReportTable fleetSummary(AccessScope scope, Set<Long> vesselIds) {
        Map<Long, MaintenanceMetrics.VesselDueCounts> dueByVessel = maintenance.perVessel(vesselIds);
        long openTotal = openRequests(vesselIds);

        List<List<String>> rows = fleet.vesselSummaries(vesselIds).stream().<List<String>>map(v -> {
            MaintenanceMetrics.VesselDueCounts due = dueByVessel.getOrDefault(v.id(),
                    new MaintenanceMetrics.VesselDueCounts(0, 0, 0));
            long open = openRequests(Set.of(v.id()));
            return List.of(value(v.name()), value(v.imoNumber()), value(v.vesselType()), value(v.flag()),
                    v.status() == null ? "—" : v.status().name().replace('_', ' '),
                    value(v.organizationName()), String.valueOf(v.spareCount()),
                    String.valueOf(due.total()), String.valueOf(due.dueSoon()), String.valueOf(due.overdue()),
                    String.valueOf(open));
        }).toList();

        return table(ReportCatalogue.FLEET_SUMMARY, scope,
                List.of(ReportTable.text("Vessel"), ReportTable.text("IMO"), ReportTable.text("Type"),
                        ReportTable.text("Flag"), ReportTable.text("Status"), ReportTable.text("Organization"),
                        ReportTable.number("Spares"), ReportTable.number("Tracked"), ReportTable.number("Due soon"),
                        ReportTable.number("Overdue"), ReportTable.number("Open requests")),
                rows, List.of(new ReportTable.Total("Vessels", String.valueOf(rows.size())),
                        new ReportTable.Total("Spares", String.valueOf(fleet.spareCount(vesselIds))),
                        new ReportTable.Total("Open requests", String.valueOf(openTotal))));
    }

    private long openRequests(Set<Long> vesselIds) {
        return requests.countsByStatus(vesselIds).entrySet().stream()
                .filter(e -> !CLOSED.contains(e.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    private ReportTable partsInventory(AccessScope scope, Set<Long> vesselIds) {
        List<FleetMetrics.PartShortage> parts = fleet.parts(vesselIds, MAX_ROWS);

        List<List<String>> rows = parts.stream().<List<String>>map(p -> List.of(
                value(p.vesselName()), value(p.name()), value(p.partNumber()), value(p.location()),
                String.valueOf(p.quantityOnHand()), String.valueOf(p.minimumQuantity()),
                p.quantityOnHand() < p.minimumQuantity() ? "Below minimum" : "In stock")).toList();

        long below = parts.stream().filter(p -> p.quantityOnHand() < p.minimumQuantity()).count();
        return table(ReportCatalogue.PARTS_INVENTORY, scope,
                List.of(ReportTable.text("Vessel"), ReportTable.text("Part"), ReportTable.text("Part number"),
                        ReportTable.text("Stored"), ReportTable.number("On hand"), ReportTable.number("Minimum"),
                        ReportTable.text("State")),
                rows, List.of(new ReportTable.Total("Parts", String.valueOf(rows.size())),
                        new ReportTable.Total("Below minimum", String.valueOf(below))));
    }

    // --------------------------------------------------------------- internals

    private static final Set<ServiceRequestStatus> CLOSED = Set.of(
            ServiceRequestStatus.COMPLETED, ServiceRequestStatus.CLOSED_NO_COST, ServiceRequestStatus.REJECTED);

    private ReportTable table(ReportCatalogue report, AccessScope scope, List<ReportTable.Column> columns,
                              List<List<String>> rows, List<ReportTable.Total> totals) {
        String who = users.find(scope.userId()).map(UserDirectory.UserRef::fullName).orElse("SeaStella");
        return new ReportTable(report.key(), report.title(), report.description(), scopeNote(scope),
                Instant.now(), who, columns, rows, totals);
    }

    /** Says on the report itself whose data it covers, so a printed page is never ambiguous. */
    private String scopeNote(AccessScope scope) {
        int vessels = scope.vesselIds().size();
        return switch (scope.kind()) {
            case PLATFORM -> "All organizations and vessels";
            case ORGANIZATION, ORGANIZATION_SET -> vessels + (vessels == 1 ? " vessel" : " vessels")
                    + " in " + (scope.organizationIds().size() == 1 ? "this organization"
                    : scope.organizationIds().size() + " organizations");
            case VESSEL_SET -> vessels == 1 ? "One vessel" : vessels + " vessels";
            case JOB_SET -> "Assigned jobs only";
        };
    }

    private static String value(String text) {
        return text == null || text.isBlank() ? "—" : text;
    }

    private static String money(BigDecimal amount, String currency) {
        return amount == null ? "—" : amount.toPlainString() + " " + (currency == null ? "" : currency);
    }
}
