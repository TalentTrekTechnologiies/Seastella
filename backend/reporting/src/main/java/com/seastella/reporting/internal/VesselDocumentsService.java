package com.seastella.reporting.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.ForbiddenException;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
import com.seastella.core.api.time.BusinessTime;
import com.seastella.fleet.api.DocumentDirectory.DocumentType;
import com.seastella.fleet.api.FleetMetrics;
import com.seastella.fleet.api.VesselRecords;
import com.seastella.fleet.api.VesselRecords.VesselParticulars;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeGuard;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.identity.api.UserDirectory;
import com.seastella.maintenance.api.MaintenanceMetrics;
import com.seastella.servicerequest.api.ServiceRequestMetrics;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The documents Thawe Marine issues for a vessel: the Shore Based Maintenance
 * Certificate and the QM-F11 pre-survey form (developer brief, 27 Oct 2026).
 *
 * <p>Who may do what is decided here, on the server, whatever the screen
 * shows: an SBMC is the Platform Administrator's alone to issue or revoke; a
 * pre-survey form is the Platform Administrator's for any vessel and a Service
 * Coordinator's for the organizations they serve. Both are printed from the
 * vessel's own records - a wrong call sign is corrected on the vessel, not
 * typed into the certificate.
 */
@Service
class VesselDocumentsService {

    /** The sample runs 21 Dec 2025 to 22 Dec 2026: a year and a day. Editable in the preview. */
    static LocalDate defaultExpiry(LocalDate issue) {
        return issue.plusYears(1).plusDays(1);
    }

    private static final Pattern MMSI = Pattern.compile("^\\d{9}$");
    private static final Pattern IMO = Pattern.compile("^\\d{7}$");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TITLE_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    static final String ISSUER = "Thawe Marine Pte Ltd";

    private final VesselRecords records;
    private final FleetMetrics fleet;
    private final MaintenanceMetrics maintenance;
    private final ServiceRequestMetrics requests;
    private final VesselCertificateRepository certificates;
    private final PreSurveyFormRepository forms;
    private final CertificateNumbers numbers;
    private final SbmcRenderer sbmc;
    private final PreSurveyRenderer preSurvey;
    private final ScopeResolver scopes;
    private final ScopeGuard scopeGuard;
    private final UserDirectory users;
    private final AuditService audit;
    private final ObjectMapper json;
    private final DateTimeFormatter formDates;
    private final String formDatePattern;

    VesselDocumentsService(VesselRecords records, FleetMetrics fleet, MaintenanceMetrics maintenance,
                           ServiceRequestMetrics requests, VesselCertificateRepository certificates,
                           PreSurveyFormRepository forms, CertificateNumbers numbers, SbmcRenderer sbmc,
                           PreSurveyRenderer preSurvey, ScopeResolver scopes, ScopeGuard scopeGuard,
                           UserDirectory users, AuditService audit, ObjectMapper json,
                           @Value("${seastella.pre-survey.date-format:d MMM yyyy}") String formDatePattern) {
        this.records = records;
        this.fleet = fleet;
        this.maintenance = maintenance;
        this.requests = requests;
        this.certificates = certificates;
        this.forms = forms;
        this.numbers = numbers;
        this.sbmc = sbmc;
        this.preSurvey = preSurvey;
        this.scopes = scopes;
        this.scopeGuard = scopeGuard;
        this.users = users;
        this.audit = audit;
        this.json = json;
        this.formDatePattern = formDatePattern;
        this.formDates = DateTimeFormatter.ofPattern(formDatePattern, Locale.ENGLISH);
    }

    // ================================================================ health

    record Health(long spares, long tracked, long overdue, long dueSoon, long openRequests, long partShortages) {}

    /** The same numbers as the vessel's row on the fleet health card. */
    @Transactional(readOnly = true)
    Health health(Long vesselId) {
        scopeGuard.assertVessel(vesselId);
        Set<Long> one = Set.of(vesselId);
        MaintenanceMetrics.VesselDueCounts due = maintenance.perVessel(one)
                .getOrDefault(vesselId, new MaintenanceMetrics.VesselDueCounts(0, 0, 0));
        return new Health(fleet.spareCount(one), due.total(), maintenance.overdueCount(one),
                maintenance.dueSoonCount(one, 15), requests.openCount(one), fleet.partShortageCount(one));
    }

    // ================================================================== SBMC

    record Missing(String field, String label) {}

    record SbmcPreview(Long vesselId, String vesselName, String callSign, String mmsi, String imo, String ownedBy,
                       LocalDate issueDate, LocalDate expiryDate, List<Missing> missing,
                       CertificateView replaces) {}

    record SbmcRequest(LocalDate issueDate, LocalDate expiryDate, String ownedBy) {}

    record CertificateView(Long id, Long vesselId, String certificateNumber, LocalDate issueDate,
                           LocalDate expiryDate, String status, String generatedBy, Instant generatedAt,
                           String supersededBy, String revokedReason, Instant revokedAt, Long documentId,
                           String templateVersion) {}

    record FileOut(byte[] content, String fileName) {}

    @Transactional(readOnly = true)
    SbmcPreview previewSbmc(Long vesselId) {
        VesselParticulars v = vessel(vesselId);
        LocalDate issue = BusinessTime.today();
        CertificateView replaces = certificates
                .findFirstByVesselIdAndStatusOrderByIdDesc(vesselId, VesselCertificate.Status.ACTIVE.name())
                .map(c -> view(c, Map.of())).orElse(null);
        return new SbmcPreview(v.id(), v.name(), v.callSign(), v.mmsi(), v.imoNumber(), v.organizationName(),
                issue, defaultExpiry(issue), missingForSbmc(v, v.organizationName()), replaces);
    }

    /** What the certificate cannot be issued without, so the preview can send the admin to fix it. */
    static List<Missing> missingForSbmc(VesselParticulars v, String ownedBy) {
        List<Missing> out = new ArrayList<>();
        if (blank(v.name())) out.add(new Missing("name", "Vessel name"));
        if (blank(v.callSign())) out.add(new Missing("callSign", "Call sign"));
        if (blank(v.mmsi()) || !MMSI.matcher(v.mmsi()).matches()) out.add(new Missing("mmsi", "MMSI (9 digits)"));
        if (blank(v.imoNumber()) || !IMO.matcher(v.imoNumber()).matches()) out.add(new Missing("imoNumber", "IMO number (7 digits)"));
        if (blank(ownedBy)) out.add(new Missing("ownedBy", "Owned and/or managed by"));
        return out;
    }

    @Transactional
    CertificateView generateSbmc(Long vesselId, SbmcRequest body) {
        AccessScope actor = requireRole(Role.PLATFORM_ADMIN, "issue an SBMC");
        VesselParticulars v = vessel(vesselId);
        if (body == null || body.issueDate() == null) throw new ValidationException("Choose the issue date.");
        LocalDate expiry = body.expiryDate() == null ? defaultExpiry(body.issueDate()) : body.expiryDate();
        if (!expiry.isAfter(body.issueDate())) throw new ValidationException("The certificate must expire after it is issued.");
        if (expiry.isAfter(body.issueDate().plusYears(5))) throw new ValidationException("Keep the validity within five years.");
        String ownedBy = blank(body.ownedBy()) ? v.organizationName() : body.ownedBy().trim();
        if (ownedBy != null && ownedBy.length() > 200) throw new ValidationException("Keep \"Owned and/or managed by\" under 200 characters.");
        List<Missing> missing = missingForSbmc(v, ownedBy);
        if (!missing.isEmpty()) {
            throw new ValidationException("Add these to the vessel first: "
                    + String.join(", ", missing.stream().map(Missing::label).toList()) + ".");
        }

        String number = numbers.nextSbmcNumber();
        SbmcRenderer.Fields fields = new SbmcRenderer.Fields(number, v.name(), v.callSign(), v.mmsi(), v.imoNumber(),
                ownedBy, body.issueDate(), expiry);
        byte[] pdf = sbmc.render(fields);

        VesselCertificate previous = certificates
                .findFirstByVesselIdAndStatusOrderByIdDesc(vesselId, VesselCertificate.Status.ACTIVE.name())
                .orElse(null);
        Long documentId = records.file(new VesselRecords.GeneratedFile(vesselId, DocumentType.CERTIFICATE,
                "Shore Based Maintenance Certificate (SBMC)", number, ISSUER, body.issueDate(), expiry,
                "SBMC-" + number + ".pdf", pdf, previous == null ? null : previous.getDocumentId()));

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("certificateNumber", number);
        snapshot.put("vesselName", SbmcRenderer.vesselLine(v.name()));
        snapshot.put("callSign", v.callSign());
        snapshot.put("mmsi", v.mmsi());
        snapshot.put("imo", v.imoNumber());
        snapshot.put("ownedBy", ownedBy);
        snapshot.put("issueDate", body.issueDate().toString());
        snapshot.put("expiryDate", expiry.toString());
        VesselCertificate saved = certificates.save(new VesselCertificate(vesselId, v.organizationId(), number,
                body.issueDate(), expiry, write(snapshot), SbmcRenderer.TEMPLATE_VERSION, documentId,
                actor.userId(), actor.role().name(), Instant.now()));

        if (previous != null) {
            previous.supersededBy(saved.getId());
            certificates.save(previous);
            audit.record(entry(actor, AuditAction.CERTIFICATE_SUPERSEDED, previous)
                    .before(AuditJson.of("certificateNumber", previous.getCertificateNumber(),
                            "status", VesselCertificate.Status.ACTIVE.name()))
                    .after(AuditJson.of("status", VesselCertificate.Status.SUPERSEDED.name(), "supersededBy", number))
                    .build());
        }
        audit.record(entry(actor, AuditAction.CERTIFICATE_GENERATED, saved)
                .after(AuditJson.of("certificateNumber", number, "type", "SBMC", "vesselName", v.name(),
                        "callSign", v.callSign(), "mmsi", v.mmsi(), "imo", v.imoNumber(), "ownedBy", ownedBy,
                        "issueDate", body.issueDate(), "expiryDate", expiry,
                        "templateVersion", SbmcRenderer.TEMPLATE_VERSION, "documentId", documentId,
                        "replaces", previous == null ? null : previous.getCertificateNumber()))
                .build());
        return view(saved, Map.of());
    }

    @Transactional(readOnly = true)
    List<CertificateView> certificates(Long vesselId) {
        requireAdminOrServingCoordinator(vesselId, "see this vessel's certificates");
        List<VesselCertificate> found = certificates.findByVesselIdOrderByIdDesc(vesselId);
        Map<Long, String> numbersById = new LinkedHashMap<>();
        found.forEach(c -> numbersById.put(c.getId(), c.getCertificateNumber()));
        return found.stream().map(c -> view(c, numbersById)).toList();
    }

    /** The certificate exactly as issued - the stored file, never a re-render. */
    @Transactional
    FileOut downloadCertificate(Long certificateId) {
        VesselCertificate c = certificates.findById(certificateId)
                .orElseThrow(() -> NotFoundException.ofResource("Certificate", certificateId));
        AccessScope actor = requireAdminOrServingCoordinator(c.getVesselId(), "download this certificate");
        byte[] content = records.read(c.getDocumentId());
        audit.record(entry(actor, AuditAction.CERTIFICATE_DOWNLOADED, c)
                .after(AuditJson.of("certificateNumber", c.getCertificateNumber(), "status", c.getStatus().name()))
                .build());
        return new FileOut(content, "SBMC-" + c.getCertificateNumber() + ".pdf");
    }

    @Transactional
    CertificateView revoke(Long certificateId, String reason) {
        AccessScope actor = requireRole(Role.PLATFORM_ADMIN, "revoke an SBMC");
        VesselCertificate c = certificates.findById(certificateId)
                .orElseThrow(() -> NotFoundException.ofResource("Certificate", certificateId));
        scopeGuard.assertVessel(c.getVesselId());
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) throw new ValidationException("Say why the certificate is being revoked.");
        if (why.length() > 500) throw new ValidationException("Keep the reason under 500 characters.");
        if (c.getStatus() == VesselCertificate.Status.REVOKED) throw new WorkflowException("That certificate is already revoked.");

        VesselCertificate.Status before = c.getStatus();
        c.revoke(why, actor.userId(), Instant.now());
        certificates.save(c);
        // Out of the vessel's current documents, so it no longer warns of its expiry.
        records.withdraw(c.getDocumentId(), "SBMC " + c.getCertificateNumber() + " revoked: " + why);
        audit.record(entry(actor, AuditAction.CERTIFICATE_REVOKED, c)
                .before(AuditJson.of("certificateNumber", c.getCertificateNumber(), "status", before.name()))
                .after(AuditJson.of("status", VesselCertificate.Status.REVOKED.name(), "reason", why))
                .build());
        return view(c, Map.of());
    }

    private CertificateView view(VesselCertificate c, Map<Long, String> numbersById) {
        String by = users.find(c.getGeneratedByUserId()).map(UserDirectory.UserRef::fullName).orElse(null);
        String supersededBy = c.getSupersededById() == null ? null
                : numbersById.getOrDefault(c.getSupersededById(),
                certificates.findById(c.getSupersededById()).map(VesselCertificate::getCertificateNumber).orElse(null));
        return new CertificateView(c.getId(), c.getVesselId(), c.getCertificateNumber(), c.getIssueDate(),
                c.getExpiryDate(), c.getStatus().name(), by, c.getGeneratedAt(), supersededBy, c.getRevokedReason(),
                c.getRevokedAt(), c.getDocumentId(), c.getTemplateVersion());
    }

    // ============================================================ pre-survey

    record CellView(String key, String label, String value, boolean missing, List<String> options) {}

    record RowView(String label, List<CellView> cells) {}

    record SectionView(String title, CellView status, CellView error, List<RowView> rows) {}

    /** {@code dateExample}: how a date prints, so the screen can say so in the client's own terms. */
    record PreSurveyDraft(Long vesselId, String vesselName, String dateExample, List<SectionView> sections,
                          long missingCount) {}

    record PreSurveyRequest(Map<String, String> values, String remarks) {}

    record PreSurveyView(Long id, Long vesselId, Instant generatedAt, String generatedBy, String generatedByRole,
                         int typedOver, String remarks, Long documentId) {}

    @Transactional(readOnly = true)
    PreSurveyDraft preSurveyDraft(Long vesselId) {
        requireAdminOrServingCoordinator(vesselId, "prepare a pre-survey form for this vessel");
        VesselParticulars v = vessel(vesselId);
        PreSurveyAssembler.Draft draft = assemble(v);
        List<SectionView> sections = draft.sections().stream().map(s -> new SectionView(s.title(),
                cell(s.status()), cell(s.error()),
                s.rows().stream().map(r -> new RowView(r.label(), r.cells().stream().map(this::cell).toList())).toList()))
                .toList();
        long missing = draft.cells().stream().filter(PreSurveyAssembler.Cell::missing).count();
        return new PreSurveyDraft(v.id(), v.name(), formDates.format(LocalDate.of(2036, 4, 30)), sections, missing);
    }

    @Transactional
    PreSurveyView generatePreSurvey(Long vesselId, PreSurveyRequest body) {
        AccessScope actor = requireAdminOrServingCoordinator(vesselId, "prepare a pre-survey form for this vessel");
        VesselParticulars v = vessel(vesselId);
        PreSurveyAssembler.Draft draft = assemble(v);
        Map<String, String> recorded = draft.values();
        Map<String, String> printed = new LinkedHashMap<>(recorded);

        // Typed values change this form only. Unknown keys are ignored: only the form's own fields print.
        Map<String, Map<String, String>> typedOver = new LinkedHashMap<>();
        if (body != null && body.values() != null) {
            for (Map.Entry<String, String> e : body.values().entrySet()) {
                if (!recorded.containsKey(e.getKey())) continue;
                String typed = e.getValue() == null ? "" : PreSurveyRenderer.clean(e.getValue());
                if (typed.length() > 200) throw new ValidationException("Keep each value under 200 characters.");
                String was = recorded.get(e.getKey()) == null ? "" : recorded.get(e.getKey());
                if (!typed.equals(was)) {
                    printed.put(e.getKey(), typed);
                    Map<String, String> change = new LinkedHashMap<>();
                    change.put("recorded", was);
                    change.put("typed", typed);
                    typedOver.put(e.getKey(), change);
                }
            }
        }
        String remarks = body == null || body.remarks() == null || body.remarks().isBlank() ? null : body.remarks().trim();
        if (remarks != null && remarks.length() > 1000) throw new ValidationException("Keep the remarks under 1000 characters.");

        byte[] pdf = preSurvey.render(printed, remarks);
        LocalDate today = BusinessTime.today();
        Long documentId = records.file(new VesselRecords.GeneratedFile(vesselId, DocumentType.REPORT,
                "Pre-Survey Form QM-F11, " + TITLE_DATE.format(today), null, null, null, null,
                "QM-F11-" + v.imoNumber() + "-" + FILE_DATE.format(today) + ".pdf", pdf, null));
        PreSurveyForm saved = forms.save(new PreSurveyForm(vesselId, v.organizationId(), write(printed),
                typedOver.isEmpty() ? null : write(typedOver), remarks, PreSurveyRenderer.TEMPLATE_VERSION,
                documentId, actor.userId(), actor.role().name(), Instant.now()));

        audit.record(AuditEntry.builder()
                .actor(actor.userId(), actor.role().name())
                .action(AuditAction.PRE_SURVEY_FORM_GENERATED)
                .entity("PreSurveyForm", saved.getId())
                .scope(v.organizationId(), vesselId)
                .after(AuditJson.of("vesselName", v.name(), "form", "QM-F11", "generatedByRole", actor.role().name(),
                        "typedOver", typedOver.size(), "overrides", typedOver.isEmpty() ? null : write(typedOver),
                        "remarks", remarks, "documentId", documentId,
                        "templateVersion", PreSurveyRenderer.TEMPLATE_VERSION))
                .build());
        return view(saved);
    }

    @Transactional(readOnly = true)
    List<PreSurveyView> preSurveyForms(Long vesselId) {
        requireAdminOrServingCoordinator(vesselId, "see this vessel's pre-survey forms");
        return forms.findByVesselIdOrderByIdDesc(vesselId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    FileOut downloadPreSurvey(Long formId) {
        PreSurveyForm f = forms.findById(formId).orElseThrow(() -> NotFoundException.ofResource("PreSurveyForm", formId));
        requireAdminOrServingCoordinator(f.getVesselId(), "download this form");
        String imo = records.particulars(f.getVesselId()).map(VesselParticulars::imoNumber).orElse("vessel");
        LocalDate day = f.getGeneratedAt().atZone(BusinessTime.ZONE).toLocalDate();
        return new FileOut(records.read(f.getDocumentId()), "QM-F11-" + imo + "-" + FILE_DATE.format(day) + ".pdf");
    }

    private PreSurveyAssembler.Draft assemble(VesselParticulars v) {
        Set<Long> one = Set.of(v.id());
        List<ServiceRequestStatus> open = Arrays.stream(ServiceRequestStatus.values())
                .filter(s -> !s.isTerminal()).toList();
        return new PreSurveyAssembler(records.register(v.id()), maintenance.overdue(one, 1000),
                requests.queue(one, open, 500), BusinessTime.today(), formDates).assemble(v);
    }

    private CellView cell(PreSurveyAssembler.Cell c) {
        return c == null ? null : new CellView(c.key(), c.label(), c.value(), c.missing(), c.options());
    }

    private PreSurveyView view(PreSurveyForm f) {
        int typed = 0;
        if (f.getOverrides() != null) {
            try {
                typed = json.readTree(f.getOverrides()).size();
            } catch (JsonProcessingException ignored) {
                // A snapshot that does not parse still downloads; the count is only a hint.
            }
        }
        return new PreSurveyView(f.getId(), f.getVesselId(), f.getGeneratedAt(),
                users.find(f.getGeneratedByUserId()).map(UserDirectory.UserRef::fullName).orElse(null),
                f.getGeneratedByRole(), typed, f.getRemarks(), f.getDocumentId());
    }

    // ============================================================= internals

    private VesselParticulars vessel(Long vesselId) {
        return records.particulars(vesselId).orElseThrow(() -> NotFoundException.ofResource("Vessel", vesselId));
    }

    private AccessScope requireRole(Role role, String what) {
        AccessScope actor = scopes.currentScope();
        if (actor.role() != role) throw ForbiddenException.ofAction(what);
        return actor;
    }

    /**
     * The Platform Administrator, for any vessel; a Service Coordinator, for the
     * organizations they serve - outside those the answer is "not allowed", as
     * the brief asks, rather than "not found".
     */
    private AccessScope requireAdminOrServingCoordinator(Long vesselId, String what) {
        AccessScope actor = scopes.currentScope();
        if (actor.role() == Role.PLATFORM_ADMIN) return actor;
        if (actor.role() == Role.SERVICE_COORDINATOR && actor.permitsVessel(vesselId)) return actor;
        throw ForbiddenException.ofAction(what);
    }

    private static AuditEntry.Builder entry(AccessScope actor, String action, VesselCertificate c) {
        return AuditEntry.builder()
                .actor(actor.userId(), actor.role().name())
                .action(action)
                .entity("VesselCertificate", c.getId())
                .scope(c.getOrganizationId(), c.getVesselId());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not record the document's values", e);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
