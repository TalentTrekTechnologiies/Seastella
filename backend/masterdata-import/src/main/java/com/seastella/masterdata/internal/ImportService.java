package com.seastella.masterdata.internal;

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
import com.seastella.fleet.api.FleetDirectory;
import com.seastella.fleet.api.SpareImportGateway;
import com.seastella.fleet.api.SpareImportGateway.ExistingSpare;
import com.seastella.fleet.api.SpareImportGateway.SpareValues;
import com.seastella.fleet.api.SpareImportGateway.VesselRef;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.identity.api.UserDirectory;
import com.seastella.masterdata.internal.SpareSheet.Column;
import com.seastella.masterdata.internal.SpareSheet.ParsedRow;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * VMP master-data import (SoW s10).
 *
 * <p>Three steps, and the middle one is the point: upload stages every row with
 * what it <em>would</em> do, the administrator reads that, and only a
 * confirmation writes anything (IMP-08, IMP-09). Nothing in fleet is touched
 * before the commit, and the commit is one transaction - a file never lands
 * half-applied (NFR-03).
 *
 * <p>Scope is enforced per row, not per file: a row naming a vessel outside the
 * importer's fleet is refused as if the vessel did not exist, in the same words
 * (S-08), so an import cannot be used to discover another client's IMOs.
 */
@Service
class ImportService {

    /** VMP decimal reference: 13, 13.1, 13.1.2. */
    private static final Pattern VMP_REF = Pattern.compile("^\\d{1,4}(\\.\\d{1,4}){0,4}$");
    private static final int MAX_MESSAGES = 900;

    private final ImportBatchRepository batches;
    private final ImportRowRepository rows;
    private final SpareImportGateway fleetGateway;
    private final FleetDirectory fleet;
    private final ScopeResolver scopes;
    private final UserDirectory users;
    private final AuditService audit;
    private final ObjectMapper json;

    ImportService(ImportBatchRepository batches, ImportRowRepository rows, SpareImportGateway fleetGateway,
                  FleetDirectory fleet, ScopeResolver scopes, UserDirectory users, AuditService audit,
                  ObjectMapper json) {
        this.batches = batches;
        this.rows = rows;
        this.fleetGateway = fleetGateway;
        this.fleet = fleet;
        this.scopes = scopes;
        this.users = users;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------ views

    record Change(String field, String before, String after) {}

    record RowView(Long id, int rowNumber, String imoNumber, String vesselName, String vmpRef, String spareName,
                   String outcome, String messages, List<Change> changes, boolean applied) {}

    record BatchView(Long id, String fileName, long fileSize, String status, String uploadedBy, Instant uploadedAt,
                     String committedBy, Instant committedAt, String vessels, int rowCount, int newCount,
                     int modifiedCount, int unchangedCount, int invalidCount, int duplicateCount,
                     Integer appliedCount, boolean canCommit, List<RowView> rows) {}

    // ----------------------------------------------------------------- upload

    /**
     * Parses and stages the file. Nothing in fleet changes here, and this does
     * not count as having seen the preview: that is a separate read, and
     * committing without it is refused (S-37).
     */
    @Transactional
    BatchView upload(String fileName, byte[] content) {
        AccessScope actor = scopes.currentScope();
        if (content == null || content.length == 0) throw new ValidationException("Choose a file to upload.");
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new ValidationException("Upload the Excel template (.xlsx).");
        }

        List<ParsedRow> parsed = SpareSheet.parse(content);
        Staging staging = new Staging(actor);
        List<ImportRow> staged = new ArrayList<>();

        ImportBatch batch = batches.save(new ImportBatch(trim(fileName, 255), content.length, actor.userId(), Instant.now()));
        for (ParsedRow row : parsed) {
            staged.add(staging.classify(batch.getId(), row));
        }
        rows.saveAll(staged);

        batch.summarise(parsed.size(), staging.count(ImportRow.Outcome.NEW), staging.count(ImportRow.Outcome.MODIFIED),
                staging.count(ImportRow.Outcome.UNCHANGED), staging.count(ImportRow.Outcome.INVALID),
                staging.count(ImportRow.Outcome.DUPLICATE), staging.vesselsSummary());
        batches.save(batch);

        audit.record(entry(actor, AuditAction.IMPORT_UPLOADED, batch)
                .after(AuditJson.of("file", batch.getFileName(), "rows", batch.getRowCount(),
                        "new", batch.getNewCount(), "modified", batch.getModifiedCount(),
                        "unchanged", batch.getUnchangedCount(), "invalid", batch.getInvalidCount(),
                        "duplicate", batch.getDuplicateCount(), "vessels", batch.getVesselsSummary()))
                .build());
        return view(batch, staged, actor);
    }

    // ------------------------------------------------------------------- read

    /** The preview. Reading it is what earns the right to commit it (G8). */
    @Transactional
    BatchView detail(Long batchId) {
        AccessScope actor = scopes.currentScope();
        ImportBatch batch = visible(batchId, actor);
        if (batch.getStatus() == ImportBatch.Status.PREVIEW && batch.getUploadedByUserId().equals(actor.userId())) {
            batch.previewedBy(actor.userId(), Instant.now());
            batches.save(batch);
        }
        return view(batch, rows.findByBatchIdOrderByRowNumberAsc(batchId), actor);
    }

    @Transactional(readOnly = true)
    List<BatchView> history(int limit) {
        AccessScope actor = scopes.currentScope();
        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), 100));
        List<ImportBatch> found = actor.role() == Role.PLATFORM_ADMIN
                ? batches.findAllByOrderByIdDesc(page)
                : batches.findByUploadedByUserIdInOrderByIdDesc(colleagues(actor), page);
        return found.stream().map(b -> view(b, List.of(), actor)).toList();
    }

    // ------------------------------------------------------------------ write

    /**
     * Applies the rows that add or change something, in one transaction. Rows
     * that are unchanged, refused or duplicated are left as the record of why.
     */
    @Transactional
    void commit(Long batchId) {
        AccessScope actor = scopes.currentScope();
        ImportBatch batch = visible(batchId, actor);
        if (batch.getStatus() != ImportBatch.Status.PREVIEW) {
            throw new WorkflowException("This upload was already "
                    + (batch.getStatus() == ImportBatch.Status.COMMITTED ? "committed." : "discarded."));
        }
        // G8 / S-37: the preview is not a formality; committing unseen is refused.
        if (!actor.userId().equals(batch.getPreviewedByUserId())) {
            throw new WorkflowException("Open the preview and check what this file would change before committing it.");
        }
        List<ImportRow> staged = rows.findByBatchIdOrderByRowNumberAsc(batchId);
        List<ImportRow> applicable = staged.stream()
                .filter(r -> r.getOutcome() == ImportRow.Outcome.NEW || r.getOutcome() == ImportRow.Outcome.MODIFIED)
                .sorted(Comparator.comparing(ImportRow::getVesselId)
                        // Parents before children: 13 before 13.1 before 13.1.2.
                        .thenComparing(r -> depth(r.getVmpRef()))
                        .thenComparing(ImportRow::getRowNumber))
                .toList();
        if (applicable.isEmpty()) {
            throw new WorkflowException("There is nothing to apply in this file: no row adds or changes anything.");
        }

        int applied = 0;
        for (ImportRow row : applicable) {
            Staged values = read(row);
            if (row.getOutcome() == ImportRow.Outcome.NEW) {
                Long id = fleetGateway.create(row.getVesselId(), row.getVmpRef(), values.categoryId(), values.values());
                row.appliedAs(id);
            } else {
                fleetGateway.update(row.getSpareId(), values.categoryId(), values.values());
                row.appliedAs(row.getSpareId());
            }
            applied++;
        }
        rows.saveAll(applicable);
        batch.committed(actor.userId(), Instant.now(), applied);
        batches.save(batch);

        audit.record(entry(actor, AuditAction.IMPORT_COMMITTED, batch)
                .after(AuditJson.of("file", batch.getFileName(), "applied", applied,
                        "new", batch.getNewCount(), "modified", batch.getModifiedCount(),
                        "vessels", batch.getVesselsSummary()))
                .build());
    }

    @Transactional
    void discard(Long batchId) {
        AccessScope actor = scopes.currentScope();
        ImportBatch batch = visible(batchId, actor);
        if (batch.getStatus() != ImportBatch.Status.PREVIEW) {
            throw new WorkflowException("This upload was already "
                    + (batch.getStatus() == ImportBatch.Status.COMMITTED ? "committed." : "discarded."));
        }
        batch.discarded(Instant.now());
        batches.save(batch);
        audit.record(entry(actor, AuditAction.IMPORT_REJECTED, batch)
                .after(AuditJson.of("file", batch.getFileName(), "rows", batch.getRowCount())).build());
    }

    /** The template, empty or filled with a vessel's current spares for round-trip editing. */
    @Transactional(readOnly = true)
    byte[] template(Long vesselId) {
        AccessScope actor = scopes.currentScope();
        if (vesselId == null) return SpareSheet.template(List.of(), null);

        VesselRef vessel = fleetGateway.vessel(vesselId)
                .filter(v -> inScope(actor, v.organizationId()))
                .orElseThrow(() -> NotFoundException.ofResource("Vessel", vesselId));

        Map<Long, String> categoryCodes = new HashMap<>();
        fleet.equipmentCategories().forEach(c -> categoryCodes.put(c.id(), c.code()));

        List<Map<Column, String>> sheet = fleetGateway.sparesByVmpRef(vesselId).values().stream()
                .sorted(Comparator.comparingInt((ExistingSpare e) -> depth(e.vmpRef())).thenComparing(ExistingSpare::vmpRef))
                .map(spare -> rowFor(spare, categoryCodes, vessel.imoNumber()))
                .toList();
        return SpareSheet.template(sheet, vessel.name() + " (IMO " + vessel.imoNumber() + ")");
    }

    // -------------------------------------------------------------- staging

    private record Staged(Long categoryId, SpareValues values) {}

    /** Holds what one file needs while it is being classified, so each row is one pass. */
    private final class Staging {

        private final AccessScope actor;
        private final Map<String, Optional<VesselRef>> vesselByImo = new HashMap<>();
        private final Map<Long, Map<String, ExistingSpare>> sparesByVessel = new HashMap<>();
        private final Map<String, Integer> seen = new HashMap<>();
        private final Map<ImportRow.Outcome, Integer> counts = new LinkedHashMap<>();
        private final Set<String> vessels = new LinkedHashSet<>();
        private final Map<Long, Set<String>> refsInFile = new HashMap<>();
        private final LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        private final Map<String, FleetDirectory.CategoryRef> categories = new HashMap<>();

        Staging(AccessScope actor) {
            this.actor = actor;
            for (FleetDirectory.CategoryRef c : fleet.equipmentCategories()) {
                categories.put(key(c.code()), c);
                categories.put(key(c.name()), c);
            }
        }

        int count(ImportRow.Outcome outcome) {
            return counts.getOrDefault(outcome, 0);
        }

        String vesselsSummary() {
            String joined = String.join(", ", vessels);
            return joined.length() > 500 ? joined.substring(0, 497) + "…" : joined;
        }

        ImportRow classify(Long batchId, ParsedRow row) {
            List<String> problems = new ArrayList<>();
            String imo = row.get(Column.IMO);
            String ref = row.get(Column.VMP_REF);
            String name = row.get(Column.NAME);

            if (imo == null) problems.add("IMO Number is required.");
            if (ref == null) {
                problems.add("VMP Ref is required.");
            } else if (!VMP_REF.matcher(ref).matches()) {
                problems.add("\"" + ref + "\" is not a VMP reference. Use the decimal form, e.g. 13.1.2.");
            }

            VesselRef vessel = imo == null ? null : resolve(imo).orElse(null);
            if (imo != null && vessel == null) {
                // Same words whether the vessel is unknown or another client's (S-08).
                problems.add("No vessel with IMO " + imo + " in your fleet.");
            }
            if (vessel != null) vessels.add(vessel.name() + " (" + vessel.imoNumber() + ")");

            if (!problems.isEmpty()) {
                return staged(batchId, row, imo, ref, name, vessel, null, ImportRow.Outcome.INVALID, problems, Map.of());
            }

            String key = imo + "|" + ref;
            Integer first = seen.putIfAbsent(key, row.rowNumber());
            if (first != null) {
                problems.add("The same spare is on row " + first + " of this file.");
                return staged(batchId, row, imo, ref, name, vessel, null, ImportRow.Outcome.DUPLICATE, problems, Map.of());
            }
            refsInFile.computeIfAbsent(vessel.id(), v -> new HashSet<>()).add(ref);

            ExistingSpare existing = spares(vessel.id()).get(ref);
            FleetDirectory.CategoryRef category = category(row, problems);
            Map<Column, Object> provided = values(row, problems);

            if (existing == null) {
                if (category == null && row.get(Column.CATEGORY) == null) {
                    problems.add("This spare is new, so Equipment Category is required.");
                }
                if (name == null) problems.add("This spare is new, so Spare / Description is required.");
                parentProblem(vessel.id(), ref).ifPresent(problems::add);
            }
            if (!problems.isEmpty()) {
                return staged(batchId, row, imo, ref, name, vessel, existing, ImportRow.Outcome.INVALID, problems, Map.of());
            }

            Map<String, Change> changes = existing == null
                    ? newChanges(name, category, provided)
                    : changes(existing, category, provided);
            ImportRow.Outcome outcome = existing == null ? ImportRow.Outcome.NEW
                    : changes.isEmpty() ? ImportRow.Outcome.UNCHANGED : ImportRow.Outcome.MODIFIED;
            return staged(batchId, row, imo, ref, name == null && existing != null ? existing.values().name() : name,
                    vessel, existing, outcome, problems, changes);
        }

        private Optional<VesselRef> resolve(String imo) {
            return vesselByImo.computeIfAbsent(imo, i -> fleetGateway.vesselByImo(i)
                    .filter(v -> inScope(actor, v.organizationId())));
        }

        private Map<String, ExistingSpare> spares(Long vesselId) {
            return sparesByVessel.computeIfAbsent(vesselId, fleetGateway::sparesByVmpRef);
        }

        /** 13.1.2 needs 13.1 to exist already or to be added by this same file. */
        private Optional<String> parentProblem(Long vesselId, String ref) {
            int lastDot = ref.lastIndexOf('.');
            if (lastDot <= 0) return Optional.empty();
            String parent = ref.substring(0, lastDot);
            boolean known = spares(vesselId).containsKey(parent)
                    || refsInFile.getOrDefault(vesselId, Set.of()).contains(parent);
            return known ? Optional.empty()
                    : Optional.of("Nothing at " + parent + " on this vessel. Add the parent spare before " + ref + ".");
        }

        private FleetDirectory.CategoryRef category(ParsedRow row, List<String> problems) {
            String value = row.get(Column.CATEGORY);
            if (value == null) return null;
            FleetDirectory.CategoryRef category = categories.get(key(value));
            if (category == null) {
                problems.add("\"" + value + "\" is not an equipment category in SeaStella.");
            }
            return category;
        }

        /** Everything the row provides, checked; problems are collected rather than thrown. */
        private Map<Column, Object> values(ParsedRow row, List<String> problems) {
            Map<Column, Object> provided = new LinkedHashMap<>();
            text(row, Column.NAME, 200, "Spare / Description", provided, problems);
            text(row, Column.MAKE, 120, "Make", provided, problems);
            text(row, Column.MODEL, 120, "Model", provided, problems);
            text(row, Column.SERIAL, 120, "Serial Number", provided, problems);
            text(row, Column.SOFTWARE, 64, "Software Version", provided, problems);

            for (Column c : List.of(Column.INSTALLED, Column.EXPIRES, Column.LAST_SERVICE, Column.LAST_SURVEY, Column.LAST_APT)) {
                String raw = row.get(c);
                if (raw == null) continue;
                LocalDate date = row.date(c);
                if (date == null) {
                    problems.add(c.heading + " \"" + raw + "\" is not a date. Use a date cell, or text like 2026-03-31.");
                    continue;
                }
                if (c != Column.EXPIRES && date.isAfter(tomorrow)) {
                    problems.add(c.heading + " cannot be in the future.");
                    continue;
                }
                provided.put(c, date);
            }
            LocalDate installed = (LocalDate) provided.get(Column.INSTALLED);
            LocalDate serviced = (LocalDate) provided.get(Column.LAST_SERVICE);
            if (installed != null && serviced != null && serviced.isBefore(installed)) {
                problems.add("Last Annual Service is before the installation date.");
            }

            String criticality = row.get(Column.CRITICALITY);
            if (criticality != null) {
                String value = criticality.trim().toUpperCase(Locale.ROOT);
                if (!List.of("CRITICAL", "HIGH", "MEDIUM", "LOW").contains(value)) {
                    problems.add("Criticality must be CRITICAL, HIGH, MEDIUM or LOW.");
                } else {
                    provided.put(Column.CRITICALITY, value);
                }
            }
            String meter = row.get(Column.HOUR_METER);
            if (meter != null) {
                Boolean flag = switch (meter.trim().toLowerCase(Locale.ROOT)) {
                    case "yes", "y", "true", "1" -> Boolean.TRUE;
                    case "no", "n", "false", "0" -> Boolean.FALSE;
                    default -> null;
                };
                if (flag == null) {
                    problems.add("Has Hour Meter must be Yes or No.");
                } else {
                    provided.put(Column.HOUR_METER, flag);
                }
            }
            return provided;
        }

        private void text(ParsedRow row, Column column, int max, String label,
                          Map<Column, Object> provided, List<String> problems) {
            String value = row.get(column);
            if (value == null) return;
            if (value.length() > max) {
                problems.add(label + " is longer than " + max + " characters.");
                return;
            }
            provided.put(column, value);
        }

        private Map<String, Change> newChanges(String name, FleetDirectory.CategoryRef category, Map<Column, Object> provided) {
            Map<String, Change> changes = new LinkedHashMap<>();
            changes.put("name", new Change("Spare / Description", null, name));
            if (category != null) changes.put("category", new Change("Equipment Category", null, category.name()));
            provided.forEach((column, value) -> {
                if (column == Column.NAME) return;
                changes.put(column.name(), new Change(column.heading, null, display(value)));
            });
            return changes;
        }

        private Map<String, Change> changes(ExistingSpare existing, FleetDirectory.CategoryRef category,
                                            Map<Column, Object> provided) {
            Map<String, Change> changes = new LinkedHashMap<>();
            SpareValues current = existing.values();
            if (category != null && !category.id().equals(existing.equipmentCategoryId())) {
                changes.put("category", new Change("Equipment Category", existing.categoryCode(), category.name()));
            }
            compare(changes, Column.NAME, provided, current.name());
            compare(changes, Column.MAKE, provided, current.make());
            compare(changes, Column.MODEL, provided, current.model());
            compare(changes, Column.SERIAL, provided, current.serialNumber());
            compare(changes, Column.SOFTWARE, provided, current.softwareVersion());
            compare(changes, Column.INSTALLED, provided, current.installationDate());
            compare(changes, Column.EXPIRES, provided, current.expirationDate());
            compare(changes, Column.LAST_SERVICE, provided, current.lastAnnualServiceDate());
            compare(changes, Column.LAST_SURVEY, provided, current.lastSurveyDate());
            compare(changes, Column.LAST_APT, provided, current.lastAptDate());
            compare(changes, Column.CRITICALITY, provided, current.criticality());
            // Only switching the meter on is a change; the import never switches it off.
            Object meter = provided.get(Column.HOUR_METER);
            if (Boolean.TRUE.equals(meter) && !Boolean.TRUE.equals(current.tracksRunningHours())) {
                changes.put(Column.HOUR_METER.name(), new Change(Column.HOUR_METER.heading, "No", "Yes"));
            }
            return changes;
        }

        private void compare(Map<String, Change> changes, Column column, Map<Column, Object> provided, Object current) {
            Object value = provided.get(column);
            if (value == null || Objects.equals(display(value), display(current))) return;
            changes.put(column.name(), new Change(column.heading, display(current), display(value)));
        }

        private ImportRow staged(Long batchId, ParsedRow row, String imo, String ref, String name, VesselRef vessel,
                                 ExistingSpare existing, ImportRow.Outcome outcome, List<String> problems,
                                 Map<String, Change> changes) {
            counts.merge(outcome, 1, Integer::sum);
            Map<String, String> values = new LinkedHashMap<>();
            row.text().forEach((column, value) -> values.put(column.name(), value));
            return new ImportRow(batchId, row.rowNumber(), trim(imo, 16), trim(ref, 32), trim(name, 200),
                    vessel == null ? null : vessel.id(), existing == null ? null : existing.id(),
                    outcome, trim(String.join(" ", problems), MAX_MESSAGES),
                    write(values), changes.isEmpty() ? null : write(changes));
        }
    }

    // -------------------------------------------------------------- internals

    /** Rebuilds what a staged row would write, from the values captured at upload. */
    private Staged read(ImportRow row) {
        Map<String, String> values = readMap(row.getValuesJson());
        Map<String, FleetDirectory.CategoryRef> categories = new HashMap<>();
        fleet.equipmentCategories().forEach(c -> {
            categories.put(key(c.code()), c);
            categories.put(key(c.name()), c);
        });
        String categoryText = values.get(Column.CATEGORY.name());
        FleetDirectory.CategoryRef category = categoryText == null ? null : categories.get(key(categoryText));

        return new Staged(category == null ? null : category.id(), new SpareValues(
                values.get(Column.NAME.name()),
                values.get(Column.MAKE.name()),
                values.get(Column.MODEL.name()),
                values.get(Column.SERIAL.name()),
                values.get(Column.SOFTWARE.name()),
                date(values.get(Column.INSTALLED.name())),
                date(values.get(Column.EXPIRES.name())),
                date(values.get(Column.LAST_SERVICE.name())),
                date(values.get(Column.LAST_SURVEY.name())),
                date(values.get(Column.LAST_APT.name())),
                flag(values.get(Column.HOUR_METER.name())),
                values.get(Column.CRITICALITY.name()) == null ? null
                        : values.get(Column.CRITICALITY.name()).trim().toUpperCase(Locale.ROOT)));
    }

    private ImportBatch visible(Long batchId, AccessScope actor) {
        ImportBatch batch = batches.findById(batchId).orElseThrow(() -> NotFoundException.ofResource("Import", batchId));
        if (actor.role() != Role.PLATFORM_ADMIN && !colleagues(actor).contains(batch.getUploadedByUserId())) {
            // Another organization's upload is simply not there.
            throw NotFoundException.ofResource("Import", batchId);
        }
        return batch;
    }

    /** Uploads a Technical Head may see: their own organization's. */
    private List<Long> colleagues(AccessScope actor) {
        if (actor.organizationId() == null) return List.of(actor.userId());
        return users.activeForOrganization(Role.TECHNICAL_HEAD, actor.organizationId()).stream()
                .map(UserDirectory.UserRef::id)
                .toList();
    }

    private static boolean inScope(AccessScope actor, Long organizationId) {
        return actor.role() == Role.PLATFORM_ADMIN || Objects.equals(actor.organizationId(), organizationId);
    }

    private BatchView view(ImportBatch batch, List<ImportRow> staged, AccessScope actor) {
        Map<Long, String> names = names(staged);
        List<RowView> rowViews = staged.stream()
                .map(r -> new RowView(r.getId(), r.getRowNumber(), r.getImoNumber(),
                        r.getVesselId() == null ? null : names.get(r.getVesselId()), r.getVmpRef(), r.getSpareName(),
                        r.getOutcome().name(), r.getMessages(), changes(r), r.isApplied()))
                .toList();
        boolean canCommit = batch.getStatus() == ImportBatch.Status.PREVIEW
                && batch.getApplicableCount() > 0
                && actor.userId().equals(batch.getUploadedByUserId());
        return new BatchView(batch.getId(), batch.getFileName(), batch.getFileSize(), batch.getStatus().name(),
                userName(batch.getUploadedByUserId()), batch.getUploadedAt(),
                batch.getCommittedByUserId() == null ? null : userName(batch.getCommittedByUserId()),
                batch.getCommittedAt(), batch.getVesselsSummary(), batch.getRowCount(), batch.getNewCount(),
                batch.getModifiedCount(), batch.getUnchangedCount(), batch.getInvalidCount(),
                batch.getDuplicateCount(), batch.getAppliedCount(), canCommit, rowViews);
    }

    private Map<Long, String> names(List<ImportRow> staged) {
        Map<Long, String> names = new HashMap<>();
        staged.stream().map(ImportRow::getVesselId).filter(Objects::nonNull).distinct()
                .forEach(id -> fleetGateway.vessel(id).ifPresent(v -> names.put(id, v.name())));
        return names;
    }

    private String userName(Long userId) {
        return users.findAll(List.of(userId)).values().stream()
                .map(UserDirectory.UserRef::fullName).findFirst().orElse("Someone");
    }

    private List<Change> changes(ImportRow row) {
        if (row.getChangesJson() == null) return List.of();
        try {
            Map<String, Change> map = json.readValue(row.getChangesJson(),
                    json.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Change.class));
            return List.copyOf(map.values());
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private Map<String, String> readMap(String raw) {
        try {
            return json.readValue(raw, json.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, String.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Staged row could not be read back", e);
        }
    }

    private String write(Object value) {
        try {
            String written = json.writeValueAsString(value);
            if (written.length() > 4000) throw new ValidationException("A row in this file is too long to stage.");
            return written;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Row could not be staged", e);
        }
    }

    private static Map<Column, String> rowFor(ExistingSpare spare, Map<Long, String> categoryCodes, String imo) {
        Map<Column, String> row = new LinkedHashMap<>();
        SpareValues v = spare.values();
        row.put(Column.IMO, imo);
        row.put(Column.VMP_REF, spare.vmpRef());
        row.put(Column.CATEGORY, categoryCodes.get(spare.equipmentCategoryId()));
        row.put(Column.NAME, v.name());
        row.put(Column.MAKE, v.make());
        row.put(Column.MODEL, v.model());
        row.put(Column.SERIAL, v.serialNumber());
        row.put(Column.SOFTWARE, v.softwareVersion());
        row.put(Column.INSTALLED, display(v.installationDate()));
        row.put(Column.EXPIRES, display(v.expirationDate()));
        row.put(Column.LAST_SERVICE, display(v.lastAnnualServiceDate()));
        row.put(Column.LAST_SURVEY, display(v.lastSurveyDate()));
        row.put(Column.LAST_APT, display(v.lastAptDate()));
        row.put(Column.HOUR_METER, Boolean.TRUE.equals(v.tracksRunningHours()) ? "Yes" : "No");
        row.put(Column.CRITICALITY, v.criticality());
        row.values().removeIf(Objects::isNull);
        return row;
    }

    private static AuditEntry.Builder entry(AccessScope actor, String action, ImportBatch batch) {
        return AuditEntry.builder()
                .actor(actor.userId(), actor.role().name())
                .action(action)
                .entity("ImportBatch", batch.getId())
                .scope(actor.organizationId(), null);
    }

    private static int depth(String vmpRef) {
        return vmpRef == null ? 0 : (int) vmpRef.chars().filter(c -> c == '.').count();
    }

    private static LocalDate date(String value) {
        try {
            return value == null ? null : LocalDate.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Boolean flag(String value) {
        if (value == null) return null;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "yes", "y", "true", "1" -> Boolean.TRUE;
            case "no", "n", "false", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static String display(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String key(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String t = value.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
