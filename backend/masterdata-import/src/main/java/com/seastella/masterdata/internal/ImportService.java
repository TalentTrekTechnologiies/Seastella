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
import com.seastella.fleet.api.SpareImportGateway.ExistingPart;
import com.seastella.fleet.api.SpareImportGateway.PartValues;
import com.seastella.fleet.api.SpareImportGateway.VesselParticulars;
import com.seastella.masterdata.internal.ClientSheetReader.CriticalSpareRow;
import com.seastella.masterdata.internal.ClientSheetReader.VesselDetails;
import com.seastella.masterdata.internal.SpareSheet.Column;
import com.seastella.masterdata.internal.SpareSheet.ParsedRow;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    record RowView(Long id, String kind, int rowNumber, String imoNumber, String vesselName, String vmpRef,
                   String spareName, String equipmentLabel, String outcome, String messages,
                   List<Change> changes, boolean applied) {}

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
        return upload(fileName, content, null);
    }

    /**
     * As above, into one chosen vessel when {@code vesselId} is given: rows that
     * do not name a vessel are read as that vessel's, and rows naming another
     * are refused.
     */
    @Transactional
    BatchView upload(String fileName, byte[] content, Long vesselId) {
        AccessScope actor = scopes.currentScope();
        if (content == null || content.length == 0) throw new ValidationException("Choose a file to upload.");
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new ValidationException("Upload the Excel template (.xlsx).");
        }

        SheetSource source = SheetSource.of(content);
        // Only particulars the file itself states are offered as vessel changes.
        boolean fileNamesVessel = source.vessel() != null;
        if (vesselId != null) {
            VesselRef target = fleetGateway.vessel(vesselId)
                    .filter(v -> inScope(actor, v.organizationId()))
                    .orElseThrow(() -> NotFoundException.ofResource("Vessel", vesselId));
            source = source.intoVessel(target.imoNumber(), target.name());
        }
        Staging staging = new Staging(actor);
        List<ImportRow> staged = new ArrayList<>();

        ImportBatch batch = batches.save(new ImportBatch(trim(fileName, 255), content.length, actor.userId(), Instant.now()));
        // The vessel first: the preview reads top to bottom, and its equipment
        // and spares mean nothing until you know which ship they belong to.
        if (fileNamesVessel) {
            staged.add(staging.classifyVessel(batch.getId(), source.vessel()));
        }
        for (ParsedRow row : source.equipment()) {
            staged.add(staging.classify(batch.getId(), row));
        }
        for (CriticalSpareRow row : source.criticalSpares()) {
            staged.add(staging.classifySpare(batch.getId(), row, source.vessel()));
        }
        rows.saveAll(staged);

        batch.summarise(staged.size(), staging.count(ImportRow.Outcome.NEW), staging.count(ImportRow.Outcome.MODIFIED),
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

    /**
     * Reads only the vessel particulars from a file, changing nothing. An
     * all-empty answer means the file names no vessel (our template, or a
     * sheet without a header block).
     */
    VesselDetails vesselDetails(byte[] content) {
        if (content == null || content.length == 0) throw new ValidationException("Choose a file to upload.");
        VesselDetails found = SheetSource.of(content).vessel();
        return found != null ? found : new VesselDetails(null, null, null, null, null, null, null, null, null);
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
                        // The vessel, then its equipment, then the spares that hang on it:
                        // each kind needs the one before it to be in place.
                        .thenComparing(r -> r.getKind().ordinal())
                        // Parents before children: 13 before 13.1 before 13.1.2.
                        .thenComparing(r -> depth(r.getVmpRef()))
                        .thenComparing(ImportRow::getRowNumber))
                .toList();
        if (applicable.isEmpty()) {
            throw new WorkflowException("There is nothing to apply in this file: no row adds or changes anything.");
        }

        int applied = 0;
        for (ImportRow row : applicable) {
            switch (row.getKind()) {
                case VESSEL -> {
                    fleetGateway.updateVessel(row.getVesselId(), readVessel(row));
                    row.appliedToVessel();
                }
                case EQUIPMENT -> {
                    Staged values = read(row);
                    if (row.getOutcome() == ImportRow.Outcome.NEW) {
                        row.appliedAs(fleetGateway.create(row.getVesselId(), row.getVmpRef(),
                                values.categoryId(), values.values()));
                    } else {
                        fleetGateway.update(row.getSpareId(), values.categoryId(), values.values());
                        row.appliedAs(row.getSpareId());
                    }
                }
                case CRITICAL_SPARE -> {
                    PartValues values = readPart(row);
                    if (row.getOutcome() == ImportRow.Outcome.NEW) {
                        row.appliedAsPart(fleetGateway.createCriticalSpare(row.getVesselId(), values));
                    } else {
                        fleetGateway.updateCriticalSpare(row.getPartId(), values);
                        row.appliedAsPart(row.getPartId());
                    }
                }
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
        private final Map<Long, Map<String, ExistingPart>> partsByVessel = new HashMap<>();
        /** Categories worked out for rows in this same file, so children can inherit them. */
        private final Map<String, FleetDirectory.CategoryRef> categoryByRef = new HashMap<>();

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
                return staged(batchId, row, imo, ref, name, vessel, null, null,
                        ImportRow.Outcome.INVALID, problems, Map.of());
            }

            String key = imo + "|" + ref;
            Integer first = seen.putIfAbsent(key, row.rowNumber());
            if (first != null) {
                problems.add("The same spare is on row " + first + " of this file.");
                return staged(batchId, row, imo, ref, name, vessel, null, null,
                        ImportRow.Outcome.DUPLICATE, problems, Map.of());
            }
            refsInFile.computeIfAbsent(vessel.id(), v -> new HashSet<>()).add(ref);

            ExistingSpare existing = spares(vessel.id()).get(ref);
            FleetDirectory.CategoryRef category = category(row, problems);  // may be inferred below
            Map<Column, Object> provided = values(row, problems);

            if (existing == null && category == null && row.get(Column.CATEGORY) == null) {
                category = inferCategory(vessel.id(), ref, name);
            }
            if (existing == null) {
                if (category == null && row.get(Column.CATEGORY) == null) {
                    problems.add("This equipment is new and its category could not be worked out from its name "
                            + "or its parent. Add an Equipment Category column, or use the SeaStella template.");
                }
                if (name == null) problems.add("This spare is new, so Spare / Description is required.");
                parentProblem(vessel.id(), ref).ifPresent(problems::add);
            }
            if (!problems.isEmpty()) {
                return staged(batchId, row, imo, ref, name, vessel, existing, category,
                        ImportRow.Outcome.INVALID, problems, Map.of());
            }

            Map<String, Change> changes = existing == null
                    ? newChanges(name, category, provided)
                    : changes(existing, category, provided);
            ImportRow.Outcome outcome = existing == null ? ImportRow.Outcome.NEW
                    : changes.isEmpty() ? ImportRow.Outcome.UNCHANGED : ImportRow.Outcome.MODIFIED;
            return staged(batchId, row, imo, ref, name == null && existing != null ? existing.values().name() : name,
                    vessel, existing, category, outcome, problems, changes);
        }

        // --------------------------------------------------------- the vessel

        /**
         * The vessel's own particulars, as the top of a client's sheet states
         * them.
         *
         * <p>The vessel has to exist already. A spreadsheet may correct what we
         * hold about a ship, but it may not conjure one: an IMO number is unique
         * across the platform, so a mistyped digit here would permanently claim
         * a number belonging to a real vessel somewhere else.
         */
        ImportRow classifyVessel(Long batchId, VesselDetails details) {
            List<String> problems = new ArrayList<>();
            String imo = details.imoNumber();
            if (imo == null) {
                problems.add("This file gives vessel details but no IMO number, "
                        + "so there is no way to tell which vessel they belong to.");
            }
            VesselRef vessel = imo == null ? null : resolve(imo).orElse(null);
            if (imo != null && vessel == null) {
                // Same words whether the vessel is unknown or another client's (S-08).
                problems.add("No vessel with IMO " + imo + " in your fleet. "
                        + "Add the vessel first, then import this file to fill in its details.");
            }
            if (vessel != null) vessels.add(vessel.name() + " (" + vessel.imoNumber() + ")");

            Map<String, String> values = vesselValues(details);
            if (!problems.isEmpty()) {
                return stagedVessel(batchId, imo, details.name(), null, ImportRow.Outcome.INVALID,
                        problems, values, Map.of());
            }

            Map<String, Change> changes = vesselChanges(fleetGateway.particulars(vessel.id()), details, problems);
            if (!problems.isEmpty()) {
                return stagedVessel(batchId, imo, details.name(), vessel, ImportRow.Outcome.INVALID,
                        problems, values, Map.of());
            }
            // A vessel already on the platform is never "new"; the sheet either
            // changes its particulars or agrees with them.
            ImportRow.Outcome outcome = changes.isEmpty()
                    ? ImportRow.Outcome.UNCHANGED : ImportRow.Outcome.MODIFIED;
            return stagedVessel(batchId, imo, details.name(), vessel, outcome, problems, values, changes);
        }

        private Map<String, String> vesselValues(VesselDetails d) {
            Map<String, String> values = new LinkedHashMap<>();
            putIfPresent(values, "NAME", d.name());
            putIfPresent(values, "MMSI", d.mmsi());
            putIfPresent(values, "CALL_SIGN", d.callSign());
            putIfPresent(values, "FLAG", d.flag());
            putIfPresent(values, "CLASS", d.vesselClass());
            putIfPresent(values, "AREA", d.area());
            putIfPresent(values, "TYPE", d.vesselType());
            putIfPresent(values, "DWT", d.dwt());
            return values;
        }

        private Map<String, Change> vesselChanges(VesselParticulars now, VesselDetails d, List<String> problems) {
            Map<String, Change> changes = new LinkedHashMap<>();
            vesselField(changes, "NAME", "Vessel Name", d.name(), now.name(), 120, problems);
            vesselField(changes, "MMSI", "MMSI", d.mmsi(), now.mmsi(), 12, problems);
            vesselField(changes, "CALL_SIGN", "Call Sign", d.callSign(), now.callSign(), 16, problems);
            vesselField(changes, "FLAG", "Flag", d.flag(), now.flag(), 64, problems);
            vesselField(changes, "CLASS", "Class", d.vesselClass(), now.vesselClass(), 64, problems);
            vesselField(changes, "AREA", "Trading Area", d.area(), now.area(), 64, problems);
            vesselField(changes, "TYPE", "Vessel Type", d.vesselType(), now.vesselType(), 64, problems);

            if (d.dwt() != null) {
                BigDecimal dwt = decimal(d.dwt());
                if (dwt == null) {
                    problems.add("DWT \"" + d.dwt() + "\" is not a number.");
                } else if (!Objects.equals(display(dwt), display(now.dwt()))) {
                    changes.put("DWT", new Change("DWT", display(now.dwt()), display(dwt)));
                }
            }
            return changes;
        }

        private void vesselField(Map<String, Change> changes, String key, String label,
                                 String value, String current, int max, List<String> problems) {
            if (value == null) return;
            if (value.length() > max) {
                problems.add(label + " is longer than " + max + " characters.");
                return;
            }
            if (!Objects.equals(value, current)) changes.put(key, new Change(label, current, value));
        }

        // -------------------------------------------------------- critical spares

        /**
         * One line of a minimum-spares form.
         *
         * <p>Which equipment it hangs on is settled at commit, not here: the
         * equipment may be arriving in this very file and not exist yet.
         */
        ImportRow classifySpare(Long batchId, CriticalSpareRow row, VesselDetails details) {
            List<String> problems = new ArrayList<>();
            String imo = details == null ? null : details.imoNumber();
            String name = row.partName();

            if (imo == null) {
                problems.add("This file lists spares but names no vessel, "
                        + "so there is nothing to add them to.");
            }
            if (name == null || name.isBlank()) {
                problems.add("This spare has no name.");
            } else if (name.length() > 200) {
                problems.add("The spare name is longer than 200 characters.");
            }
            problems.addAll(row.warnings());

            VesselRef vessel = imo == null ? null : resolve(imo).orElse(null);
            if (imo != null && vessel == null) {
                problems.add("No vessel with IMO " + imo + " in your fleet.");
            }

            String equipment = row.equipmentName() != null ? row.equipmentName() : row.equipmentRef();
            Map<String, String> values = spareValues(row, equipment);
            if (!problems.isEmpty()) {
                return stagedSpare(batchId, row.rowNumber(), imo, name, equipment, vessel, null,
                        ImportRow.Outcome.INVALID, problems, values, Map.of());
            }

            PartValues incoming = new PartValues(name, equipment, row.equipmentRef(), null,
                    row.minimumQuantity(), row.minimumNote(), row.quantityOnHand(),
                    row.compliance(), row.remarks());

            String key = vessel.id() + "|" + incoming.key();
            Integer first = seen.putIfAbsent(key, row.rowNumber());
            if (first != null) {
                problems.add("The same spare for the same equipment is on row " + first + " of this file.");
                return stagedSpare(batchId, row.rowNumber(), imo, name, equipment, vessel, null,
                        ImportRow.Outcome.DUPLICATE, problems, values, Map.of());
            }

            ExistingPart existing = parts(vessel.id()).get(incoming.key());
            Map<String, Change> changes = existing == null
                    ? newSpareChanges(incoming)
                    : spareChanges(existing.values(), incoming);
            ImportRow.Outcome outcome = existing == null ? ImportRow.Outcome.NEW
                    : changes.isEmpty() ? ImportRow.Outcome.UNCHANGED : ImportRow.Outcome.MODIFIED;
            return stagedSpare(batchId, row.rowNumber(), imo, name, equipment, vessel, existing,
                    outcome, problems, values, changes);
        }

        private Map<String, String> spareValues(CriticalSpareRow row, String equipment) {
            Map<String, String> values = new LinkedHashMap<>();
            putIfPresent(values, "PART_NAME", row.partName());
            putIfPresent(values, "EQUIPMENT", equipment);
            putIfPresent(values, "EQUIPMENT_REF", row.equipmentRef());
            putIfPresent(values, "MIN_QTY", row.minimumQuantity() == null ? null : row.minimumQuantity().toString());
            putIfPresent(values, "MIN_NOTE", row.minimumNote());
            putIfPresent(values, "ON_HAND", row.quantityOnHand() == null ? null : row.quantityOnHand().toString());
            putIfPresent(values, "COMPLIANCE", row.compliance());
            putIfPresent(values, "REMARKS", row.remarks());
            return values;
        }

        private Map<String, Change> newSpareChanges(PartValues v) {
            Map<String, Change> changes = new LinkedHashMap<>();
            changes.put("PART_NAME", new Change("Spare Part", null, v.name()));
            if (v.equipmentName() != null) changes.put("EQUIPMENT", new Change("Equipment", null, v.equipmentName()));
            if (v.minimumQuantity() != null) changes.put("MIN_QTY", new Change("Minimum Quantity", null, v.minimumQuantity().toString()));
            if (v.minimumNote() != null) changes.put("MIN_NOTE", new Change("Minimum (as written)", null, v.minimumNote()));
            if (v.quantityOnHand() != null) changes.put("ON_HAND", new Change("Quantity On Hand", null, v.quantityOnHand().toString()));
            if (v.compliance() != null) changes.put("COMPLIANCE", new Change("Compliance", null, v.compliance()));
            if (v.remarks() != null) changes.put("REMARKS", new Change("Remarks", null, v.remarks()));
            return changes;
        }

        private Map<String, Change> spareChanges(PartValues now, PartValues v) {
            Map<String, Change> changes = new LinkedHashMap<>();
            if (v.minimumQuantity() != null && !Objects.equals(v.minimumQuantity(), now.minimumQuantity())) {
                changes.put("MIN_QTY", new Change("Minimum Quantity",
                        display(now.minimumQuantity()), v.minimumQuantity().toString()));
            }
            if (v.quantityOnHand() != null && !Objects.equals(v.quantityOnHand(), now.quantityOnHand())) {
                changes.put("ON_HAND", new Change("Quantity On Hand",
                        display(now.quantityOnHand()), v.quantityOnHand().toString()));
            }
            if (v.minimumNote() != null && !Objects.equals(v.minimumNote(), now.minimumNote())) {
                changes.put("MIN_NOTE", new Change("Minimum (as written)", now.minimumNote(), v.minimumNote()));
            }
            if (v.compliance() != null && !Objects.equals(v.compliance(), now.compliance())) {
                changes.put("COMPLIANCE", new Change("Compliance", now.compliance(), v.compliance()));
            }
            if (v.remarks() != null && !Objects.equals(v.remarks(), now.remarks())) {
                changes.put("REMARKS", new Change("Remarks", now.remarks(), v.remarks()));
            }
            return changes;
        }

        private Map<String, ExistingPart> parts(Long vesselId) {
            return partsByVessel.computeIfAbsent(vesselId, fleetGateway::criticalSparesByKey);
        }

        private static void putIfPresent(Map<String, String> values, String key, String value) {
            if (value != null && !value.isBlank()) values.put(key, value.trim());
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

        /**
         * The category for a row whose file has no category column.
         *
         * <p>A client's equipment list names the category in the equipment
         * itself - "X-Band RADAR", "ECDIS NO 1", "GPS 1" - so that is read
         * first. A sub-component rarely does ("MAGNETRON", "scanner unit FAN"),
         * and takes its parent's, which is what the VMP tree means anyway:
         * 13.1.1 is part of the radar at 13.1.
         *
         * <p>Returns null when neither answers, and the row is then refused with
         * a message rather than filed under a guess.
         */
        private FleetDirectory.CategoryRef inferCategory(Long vesselId, String ref, String name) {
            FleetDirectory.CategoryRef fromName = categoryInName(name);
            if (fromName != null) {
                categoryByRef.put(vesselId + "|" + ref, fromName);
                return fromName;
            }
            for (String parent = parentRef(ref); parent != null; parent = parentRef(parent)) {
                FleetDirectory.CategoryRef inherited = categoryByRef.get(vesselId + "|" + parent);
                if (inherited == null) {
                    ExistingSpare onVessel = spares(vesselId).get(parent);
                    if (onVessel != null && onVessel.equipmentCategoryId() != null) {
                        inherited = categories.get(key(onVessel.categoryCode()));
                    }
                }
                if (inherited != null) {
                    categoryByRef.put(vesselId + "|" + ref, inherited);
                    return inherited;
                }
            }
            return null;
        }

        /** The longest category code or name the equipment's own name contains. */
        private FleetDirectory.CategoryRef categoryInName(String name) {
            if (name == null) return null;
            String haystack = key(name);
            if (haystack.isEmpty()) return null;
            FleetDirectory.CategoryRef best = null;
            int bestLength = 0;
            for (FleetDirectory.CategoryRef c : fleet.equipmentCategories()) {
                for (String candidate : List.of(key(c.code()), key(c.name()))) {
                    // Two letters match far too much; "AIS" and "GPS" are the short ones that count.
                    if (candidate.length() < 3 || !haystack.contains(candidate)) continue;
                    if (candidate.length() > bestLength) {
                        best = c;
                        bestLength = candidate.length();
                    }
                }
            }
            return best;
        }

        private static String parentRef(String ref) {
            if (ref == null) return null;
            int lastDot = ref.lastIndexOf('.');
            return lastDot <= 0 ? null : ref.substring(0, lastDot);
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
                                 ExistingSpare existing, FleetDirectory.CategoryRef category,
                                 ImportRow.Outcome outcome, List<String> problems,
                                 Map<String, Change> changes) {
            counts.merge(outcome, 1, Integer::sum);
            Map<String, String> values = new LinkedHashMap<>();
            row.text().forEach((column, value) -> values.put(column.name(), value));
            // A client's sheet has no category column, so the category worked out
            // here is written down. Commit rebuilds what it applies from these
            // values, and would otherwise have nothing to rebuild it from.
            if (category != null) values.put(Column.CATEGORY.name(), category.code());
            return new ImportRow(batchId, ImportRow.Kind.EQUIPMENT, row.rowNumber(), trim(imo, 16), trim(ref, 32),
                    trim(name, 200), null, vessel == null ? null : vessel.id(),
                    existing == null ? null : existing.id(), null,
                    outcome, trim(String.join(" ", problems), MAX_MESSAGES),
                    write(values), changes.isEmpty() ? null : write(changes));
        }

        private ImportRow stagedVessel(Long batchId, String imo, String name, VesselRef vessel,
                                       ImportRow.Outcome outcome, List<String> problems,
                                       Map<String, String> values, Map<String, Change> changes) {
            counts.merge(outcome, 1, Integer::sum);
            // Row 1: a client's sheet states the vessel at the top, and the
            // preview should show it there too.
            return new ImportRow(batchId, ImportRow.Kind.VESSEL, 1, trim(imo, 16), null,
                    trim(name == null && vessel != null ? vessel.name() : name, 200), null,
                    vessel == null ? null : vessel.id(), null, null,
                    outcome, trim(String.join(" ", problems), MAX_MESSAGES),
                    write(values), changes.isEmpty() ? null : write(changes));
        }

        private ImportRow stagedSpare(Long batchId, int rowNumber, String imo, String name, String equipment,
                                      VesselRef vessel, ExistingPart existing, ImportRow.Outcome outcome,
                                      List<String> problems, Map<String, String> values,
                                      Map<String, Change> changes) {
            counts.merge(outcome, 1, Integer::sum);
            return new ImportRow(batchId, ImportRow.Kind.CRITICAL_SPARE, rowNumber, trim(imo, 16), null,
                    trim(name, 200), trim(equipment, 200), vessel == null ? null : vessel.id(), null,
                    existing == null ? null : existing.id(),
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

    /** The vessel particulars a staged row would write. */
    private VesselParticulars readVessel(ImportRow row) {
        Map<String, String> values = readMap(row.getValuesJson());
        return new VesselParticulars(values.get("NAME"), values.get("MMSI"), values.get("CALL_SIGN"),
                values.get("FLAG"), values.get("CLASS"), values.get("AREA"), values.get("TYPE"),
                decimal(values.get("DWT")));
    }

    /**
     * The critical spare a staged row would write.
     *
     * <p>The equipment it belongs to is resolved now rather than at upload,
     * because equipment arriving in this same file is applied first and exists
     * by the time this runs. Equipment that never turns up leaves the spare on
     * the vessel unattached, which is what a form listing a spare for equipment
     * the vessel has no record of actually means.
     */
    private PartValues readPart(ImportRow row) {
        Map<String, String> values = readMap(row.getValuesJson());
        return new PartValues(values.get("PART_NAME"), row.getEquipmentLabel(), values.get("EQUIPMENT_REF"),
                equipmentId(row.getVesselId(), values.get("EQUIPMENT_REF"), row.getEquipmentLabel()),
                wholeNumber(values.get("MIN_QTY")), values.get("MIN_NOTE"),
                wholeNumber(values.get("ON_HAND")), values.get("COMPLIANCE"), values.get("REMARKS"));
    }

    /**
     * The equipment a critical spare belongs to, by the name the form gives it.
     *
     * <p>The name is tried first and the reference second, which is the
     * opposite of how equipment rows are matched. A minimum-spares form states
     * its equipment by name - "X-Band RADAR" - and numbers its own lines 1, 2,
     * 3 down the left. Those line numbers read exactly like VMP references, so
     * trusting them first hangs the first spare on whatever equipment happens
     * to sit at VMP 1. A reference is used only when the form named no
     * equipment at all, where it is the only thing left to go on.
     */
    private Long equipmentId(Long vesselId, String ref, String label) {
        Map<String, ExistingSpare> onVessel = fleetGateway.sparesByVmpRef(vesselId);
        if (label != null) {
            Optional<Long> byName = onVessel.values().stream()
                    .filter(sp -> sp.values().name() != null && key(sp.values().name()).equals(key(label)))
                    .map(ExistingSpare::id)
                    .findFirst();
            if (byName.isPresent()) return byName.get();
        }
        // Only when the form named no equipment: a named one that is not on the
        // vessel stays unlinked rather than landing on whatever sits at that number.
        if (ref != null && label == null) {
            ExistingSpare byRef = onVessel.get(ref);
            if (byRef != null) return byRef.id();
        }
        return null;
    }

    private static Integer wholeNumber(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
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
                .map(r -> new RowView(r.getId(), r.getKind().name(), r.getRowNumber(), r.getImoNumber(),
                        r.getVesselId() == null ? null : names.get(r.getVesselId()), r.getVmpRef(),
                        r.getSpareName(), r.getEquipmentLabel(), r.getOutcome().name(), r.getMessages(),
                        changes(r), r.isApplied()))
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
