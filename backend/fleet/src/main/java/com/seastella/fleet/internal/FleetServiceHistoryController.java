package com.seastella.fleet.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.ForbiddenException;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeGuard;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.identity.api.UserDirectory;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The fleet's service history in one place (SoW §6.3, §9.3): every service,
 * repair and part replaced, across every vessel in scope - read, and added to
 * in bulk from a spreadsheet.
 *
 * <p>An upload is checked in full before anything is written. The same call
 * with {@code apply=false} returns what each row would do; with
 * {@code apply=true} it writes them all in one transaction, and only when no
 * row has a problem - a history is not something to half-import.
 */
@RestController
@RequestMapping("/api/v1/service-history")
@PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TECHNICAL_HEAD','SHIP_MANAGER')")
class FleetServiceHistoryController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int MAX_ROWS = 5000;
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d-M-uuuu"), DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("d.M.uuuu"),
            DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMM-uu", Locale.ENGLISH));

    private final SpareServiceRecordRepository records;
    private final SpareRepository spares;
    private final VesselRepository vessels;
    private final ScopeResolver scopes;
    private final ScopeGuard scopeGuard;
    private final UserDirectory users;
    private final AuditService audit;
    private final ServiceDateSync dates;

    FleetServiceHistoryController(SpareServiceRecordRepository records, SpareRepository spares,
                                  VesselRepository vessels, ScopeResolver scopes, ScopeGuard scopeGuard,
                                  UserDirectory users, AuditService audit, ServiceDateSync dates) {
        this.records = records;
        this.spares = spares;
        this.vessels = vessels;
        this.scopes = scopes;
        this.scopeGuard = scopeGuard;
        this.users = users;
        this.audit = audit;
        this.dates = dates;
    }

    // ------------------------------------------------------------------ read

    /** Newest first, across every vessel in scope or one of them. */
    @GetMapping
    @Transactional(readOnly = true)
    ResponseEntity<List<FleetRecordView>> history(@RequestParam(required = false) Long vesselId,
                                                  @RequestParam(defaultValue = "2000") int limit) {
        AccessScope scope = scopes.currentScope();
        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), 5000));
        List<SpareServiceRecord> found;
        if (vesselId != null) {
            scopeGuard.assertVessel(vesselId);
            found = records.findByVesselIdInOrderByServiceDateDescIdDesc(Set.of(vesselId), page);
        } else if (scope.isPlatformWide()) {
            found = records.findAllByOrderByServiceDateDescIdDesc(page);
        } else if (scope.vesselIds().isEmpty()) {
            found = List.of();
        } else {
            found = records.findByVesselIdInOrderByServiceDateDescIdDesc(scope.vesselIds(), page);
        }
        if (found.isEmpty()) return ResponseEntity.ok(List.of());

        Map<Long, Spare> spareById = spares.findAllById(found.stream().map(SpareServiceRecord::getSpareId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Spare::getId, Function.identity()));
        Map<Long, String> vesselNames = vessels.findAllById(found.stream().map(SpareServiceRecord::getVesselId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Vessel::getId, Vessel::getName));
        Map<Long, UserDirectory.UserRef> people = users.findAll(found.stream()
                .map(SpareServiceRecord::getRecordedByUserId).filter(Objects::nonNull).toList());

        return ResponseEntity.ok(found.stream().map(r -> {
            Spare spare = spareById.get(r.getSpareId());
            UserDirectory.UserRef who = r.getRecordedByUserId() == null ? null : people.get(r.getRecordedByUserId());
            return new FleetRecordView(r.getId(), r.getVesselId(), vesselNames.get(r.getVesselId()), r.getSpareId(),
                    spare == null ? null : spare.getPath(), spare == null ? null : spare.getName(),
                    r.getServiceDate(), r.getSource(), r.getWorkPerformed(), r.getPartsUsed(), r.getPerformedBy(),
                    r.getRequestNumber(), who == null ? null : who.fullName(), r.getNotes(), r.isRecorded());
        }).toList());
    }

    // ---------------------------------------------------------------- export

    /**
     * The history as Excel, in the upload's own columns - so a downloaded file
     * can be corrected and uploaded again, and what is already there is skipped.
     * One vessel, one item of equipment, or everything in scope.
     */
    @GetMapping("/export")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> export(@RequestParam(required = false) Long vesselId,
                                  @RequestParam(required = false) Long spareId) throws IOException {
        AccessScope scope = scopes.currentScope();
        List<SpareServiceRecord> found;
        String label;
        if (spareId != null) {
            Spare spare = spares.findById(spareId).orElseThrow(() -> NotFoundException.ofResource("Spare", spareId));
            scopeGuard.assertVessel(spare.getVesselId());
            found = records.findBySpareIdOrderByServiceDateDescIdDesc(spareId);
            label = spare.getPath() + " " + spare.getName();
        } else if (vesselId != null) {
            scopeGuard.assertVessel(vesselId);
            found = records.findByVesselIdInOrderByServiceDateDescIdDesc(Set.of(vesselId), PageRequest.of(0, MAX_ROWS));
            label = vessels.findById(vesselId).map(Vessel::getName).orElse("vessel");
        } else if (scope.isPlatformWide()) {
            found = records.findAllByOrderByServiceDateDescIdDesc(PageRequest.of(0, MAX_ROWS));
            label = "all vessels";
        } else {
            found = scope.vesselIds().isEmpty() ? List.of()
                    : records.findByVesselIdInOrderByServiceDateDescIdDesc(scope.vesselIds(), PageRequest.of(0, MAX_ROWS));
            label = "all vessels";
        }

        Map<Long, Spare> spareById = spares.findAllById(found.stream().map(SpareServiceRecord::getSpareId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Spare::getId, Function.identity()));
        Map<Long, Vessel> vesselById = vessels.findAllById(found.stream().map(SpareServiceRecord::getVesselId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(Vessel::getId, Function.identity()));

        try (Workbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Service history");
            org.apache.poi.ss.usermodel.CellStyle bold = book.createCellStyle();
            org.apache.poi.ss.usermodel.Font font = book.createFont();
            font.setBold(true);
            bold.setFont(font);
            org.apache.poi.ss.usermodel.CellStyle dateStyle = book.createCellStyle();
            dateStyle.setDataFormat(book.getCreationHelper().createDataFormat().getFormat("dd-mmm-yyyy"));

            // The upload's columns first, then what helps a reader; the upload ignores the extras.
            String[] headings = {"IMO Number", "Equipment", "Date", "Work done", "Parts replaced", "Performed by",
                    "Notes", "Vessel", "Equipment name", "Source"};
            int[] widths = {12, 12, 13, 50, 28, 26, 36, 22, 30, 20};
            Row head = sheet.createRow(0);
            for (int i = 0; i < headings.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(headings[i]);
                c.setCellStyle(bold);
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            sheet.createFreezePane(0, 1);
            int r = 1;
            for (SpareServiceRecord rec : found) {
                Spare spare = spareById.get(rec.getSpareId());
                Vessel vessel = vesselById.get(rec.getVesselId());
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(vessel == null ? "" : vessel.getImoNumber());
                row.createCell(1).setCellValue(spare == null ? "" : spare.getPath());
                Cell date = row.createCell(2);
                date.setCellValue(rec.getServiceDate());
                date.setCellStyle(dateStyle);
                row.createCell(3).setCellValue(nz(rec.getWorkPerformed()));
                row.createCell(4).setCellValue(nz(rec.getPartsUsed()));
                row.createCell(5).setCellValue(nz(rec.getPerformedBy()));
                row.createCell(6).setCellValue(nz(rec.getNotes()));
                row.createCell(7).setCellValue(vessel == null ? "" : vessel.getName());
                row.createCell(8).setCellValue(spare == null ? "" : spare.getName());
                row.createCell(9).setCellValue(rec.getRequestNumber() == null ? "Recorded" : rec.getRequestNumber());
            }
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, r - 1), 0,
                    headings.length - 1));
            book.write(out);
            String fileName = "thawe-marine-service-history-" + label.replaceAll("[^A-Za-z0-9]+", "-")
                    .replaceAll("^-|-$", "").toLowerCase(Locale.ROOT) + "-" + LocalDate.now(ZoneOffset.UTC) + ".xlsx";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(fileName).build().toString())
                    .contentType(MediaType.parseMediaType(XLSX))
                    .body(out.toByteArray());
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // -------------------------------------------------------------- template

    /** A blank sheet in the columns the upload reads. */
    @GetMapping("/template")
    ResponseEntity<byte[]> template() throws IOException {
        try (Workbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Service history");
            String[] headings = {"IMO Number", "Equipment", "Date", "Work done", "Parts replaced", "Performed by",
                    "Notes"};
            Row head = sheet.createRow(0);
            for (int i = 0; i < headings.length; i++) {
                head.createCell(i).setCellValue(headings[i]);
                sheet.setColumnWidth(i, i == 3 ? 60 * 256 : 22 * 256);
            }
            Row example = sheet.createRow(1);
            String[] sample = {"9412367", "13.1", "15-03-2025", "Magnetron replaced; performance test done",
                    "Magnetron MG5436", "Marine Electronics Pte Ltd", "Example row - replace or delete"};
            for (int i = 0; i < sample.length; i++) example.createCell(i).setCellValue(sample[i]);
            book.write(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename("thawe-marine-service-history-template.xlsx").build().toString())
                    .contentType(MediaType.parseMediaType(XLSX))
                    .body(out.toByteArray());
        }
    }

    // ---------------------------------------------------------------- upload

    /**
     * Reads a history sheet. Each row names its vessel by IMO - or the vessel
     * chosen for the upload is used - and its equipment by VMP number or by
     * name. {@code apply=false} changes nothing.
     */
    @PostMapping("/import")
    @Transactional
    ResponseEntity<UploadView> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(required = false) Long vesselId,
                                      @RequestParam(defaultValue = "false") boolean apply) throws IOException {
        AccessScope actor = scopes.currentScope();
        if (actor.role() != Role.TECHNICAL_HEAD && actor.role() != Role.PLATFORM_ADMIN) {
            throw ForbiddenException.ofAction("record service history");
        }
        if (file == null || file.isEmpty()) throw new ValidationException("Choose a file to upload.");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx")) throw new ValidationException("Upload an Excel file (.xlsx).");

        Vessel chosen = null;
        if (vesselId != null) {
            scopeGuard.assertVessel(vesselId);
            chosen = vessels.findById(vesselId).orElseThrow(() -> NotFoundException.ofResource("Vessel", vesselId));
        }

        List<Parsed> parsed = read(file.getBytes());
        if (parsed.isEmpty()) {
            throw new ValidationException("No history rows were found. The sheet needs a heading row with at least "
                    + "Equipment, Date and Work done - download the template to start from.");
        }

        Resolver resolver = new Resolver(actor, chosen);
        List<RowView> rows = new ArrayList<>();
        List<Ready> ready = new ArrayList<>();
        Set<String> inFile = new HashSet<>();
        for (Parsed p : parsed) {
            List<String> problems = new ArrayList<>(p.problems());
            Vessel vessel = resolver.vessel(p.imo(), problems);
            Spare spare = vessel == null ? null : resolver.spare(vessel, p.equipment(), problems);
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            if (p.date() != null && p.date().isAfter(today)) problems.add("The date is in the future.");
            if (spare != null && p.date() != null && spare.getInstallationDate() != null
                    && p.date().isBefore(spare.getInstallationDate())) {
                problems.add("That is before the equipment was installed (" + spare.getInstallationDate() + ").");
            }
            if (p.work() != null && p.work().length() > 2000) problems.add("Work done is over 2000 characters.");

            String status;
            String message;
            if (!problems.isEmpty()) {
                status = "ERROR";
                message = String.join(" ", problems);
            } else if (!inFile.add(spare.getId() + "|" + p.date() + "|" + p.work().toLowerCase(Locale.ROOT))
                    || records.existsBySpareIdAndServiceDateAndWorkPerformedIgnoreCase(spare.getId(), p.date(),
                    p.work())) {
                status = "SKIP";
                message = "Already in the history.";
            } else {
                status = "ADD";
                message = null;
                ready.add(new Ready(p, spare));
            }
            rows.add(new RowView(p.rowNumber(), vessel == null ? p.imo() : vessel.getName(),
                    spare == null ? p.equipment() : spare.getPath() + " " + spare.getName(),
                    p.date(), p.work(), p.parts(), p.by(), status, message));
        }

        int errors = (int) rows.stream().filter(r -> r.status().equals("ERROR")).count();
        int skipped = (int) rows.stream().filter(r -> r.status().equals("SKIP")).count();
        boolean applied = false;
        if (apply) {
            if (errors > 0) {
                throw new ValidationException(errors + (errors == 1 ? " row has a problem" : " rows have problems")
                        + ". Correct the file and upload it again - nothing was added.");
            }
            Set<Spare> touched = new HashSet<>();
            for (Ready r : ready) {
                Parsed p = r.row();
                records.save(SpareServiceRecord.recorded(r.spare().getId(), r.spare().getVesselId(), p.date(),
                        p.work(), trim(p.parts(), 1000), trim(p.by(), 200), trim(p.notes(), 1000), actor.userId()));
                touched.add(r.spare());
            }
            for (Spare spare : touched) {
                dates.applyNewestDate(spare, actor.userId());
                audit.record(AuditEntry.builder()
                        .actor(actor.userId(), actor.role().name())
                        .action(AuditAction.SERVICE_DATE_CHANGED)
                        .entity("Spare", spare.getId())
                        .scope(null, spare.getVesselId())
                        .after(AuditJson.of("source", "RECORDED", "upload", file.getOriginalFilename(),
                                "entries", ready.stream().filter(x -> x.spare().equals(spare)).count()))
                        .build());
            }
            applied = true;
        }
        return ResponseEntity.ok(new UploadView(rows.size(), ready.size(), skipped, errors, applied, rows));
    }

    // -------------------------------------------------------------- internals

    private record Parsed(int rowNumber, String imo, String equipment, LocalDate date, String work, String parts,
                          String by, String notes, List<String> problems) {}

    private record Ready(Parsed row, Spare spare) {}

    /** Finds vessels and equipment once per file, and only within scope. */
    private final class Resolver {
        private final AccessScope actor;
        private final Vessel chosen;
        private final Map<String, Vessel> byImo = new HashMap<>();
        private final Map<Long, List<Spare>> sparesByVessel = new HashMap<>();

        Resolver(AccessScope actor, Vessel chosen) {
            this.actor = actor;
            this.chosen = chosen;
        }

        Vessel vessel(String imo, List<String> problems) {
            if (imo == null) {
                if (chosen == null) problems.add("No IMO Number - add one, or choose the vessel before uploading.");
                return chosen;
            }
            String clean = imo.replaceFirst("(?i)^IMO\\s*", "").replaceAll("\\.0$", "").trim();
            if (chosen != null && !clean.equals(chosen.getImoNumber())) {
                problems.add("IMO " + clean + " is not " + chosen.getName() + ".");
                return null;
            }
            Vessel found = byImo.computeIfAbsent(clean, i -> vessels.findAll().stream()
                    .filter(v -> i.equals(v.getImoNumber()) && actor.permitsVessel(v.getId()))
                    .findFirst().orElse(null));
            // Same words whether the vessel is unknown or another client's.
            if (found == null) problems.add("No vessel with IMO " + clean + " in your fleet.");
            return found;
        }

        /** By VMP number first, then by name - which must pick out exactly one item. */
        Spare spare(Vessel vessel, String equipment, List<String> problems) {
            if (equipment == null) return null;
            List<Spare> onVessel = sparesByVessel.computeIfAbsent(vessel.getId(), spares::findByVesselIdOrderByPathAsc);
            String wanted = equipment.trim();
            for (Spare s : onVessel) {
                if (wanted.equals(s.getPath())) return s;
            }
            String key = key(wanted);
            List<Spare> named = onVessel.stream().filter(s -> key(s.getName()).equals(key)).toList();
            if (named.size() == 1) return named.get(0);
            if (named.size() > 1) {
                problems.add("\"" + wanted + "\" matches " + named.size() + " items on " + vessel.getName()
                        + " - use the VMP number instead (e.g. " + named.get(0).getPath() + ").");
            } else {
                problems.add("No equipment \"" + wanted + "\" on " + vessel.getName() + ".");
            }
            return null;
        }
    }

    private enum Col { IMO, EQUIPMENT, DATE, WORK, PARTS, BY, NOTES }

    private static Col column(String heading) {
        String h = key(heading);
        if (h.isEmpty()) return null;
        if (h.contains("imo")) return Col.IMO;
        if (h.contains("date")) return Col.DATE;
        if (h.contains("part")) return Col.PARTS;
        if (h.contains("performedby") || h.equals("by") || h.contains("engineer") || h.contains("company")
                || h.contains("servicedby") || h.contains("doneby")) return Col.BY;
        if (h.contains("work") || h.contains("description") || h.contains("done") || h.contains("service")
                || h.contains("activity")) return Col.WORK;
        if (h.contains("equipment") || h.contains("vmp") || h.contains("item") || h.contains("spare")) return Col.EQUIPMENT;
        if (h.contains("note") || h.contains("remark")) return Col.NOTES;
        return null;
    }

    private static List<Parsed> read(byte[] content) {
        try (Workbook book = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter(Locale.ENGLISH);
            for (int s = 0; s < book.getNumberOfSheets(); s++) {
                Sheet sheet = book.getSheetAt(s);
                // The heading row is the first of the top 15 naming equipment, date and work.
                for (int h = sheet.getFirstRowNum(); h <= Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + 15); h++) {
                    Row head = sheet.getRow(h);
                    if (head == null) continue;
                    Map<Col, Integer> cols = new HashMap<>();
                    for (Cell cell : head) {
                        Col c = column(formatter.formatCellValue(cell));
                        if (c != null) cols.putIfAbsent(c, cell.getColumnIndex());
                    }
                    if (cols.containsKey(Col.EQUIPMENT) && cols.containsKey(Col.DATE) && cols.containsKey(Col.WORK)) {
                        return rows(sheet, h, cols, formatter);
                    }
                }
            }
            return List.of();
        } catch (IOException | RuntimeException e) {
            throw new ValidationException("This file could not be read. Upload an Excel workbook (.xlsx).");
        }
    }

    private static List<Parsed> rows(Sheet sheet, int headRow, Map<Col, Integer> cols, DataFormatter formatter) {
        List<Parsed> out = new ArrayList<>();
        for (int r = headRow + 1; r <= sheet.getLastRowNum() && out.size() < MAX_ROWS; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String equipment = text(row, cols.get(Col.EQUIPMENT), formatter);
            String work = text(row, cols.get(Col.WORK), formatter);
            String rawDate = text(row, cols.get(Col.DATE), formatter);
            if (equipment == null && work == null && rawDate == null) continue;

            List<String> problems = new ArrayList<>();
            if (equipment == null) problems.add("Equipment is empty.");
            if (work == null) problems.add("Work done is empty.");
            LocalDate date = date(row, cols.get(Col.DATE), formatter);
            if (date == null) {
                problems.add(rawDate == null ? "Date is empty." : "\"" + rawDate + "\" is not a date.");
            }
            out.add(new Parsed(r + 1, text(row, cols.get(Col.IMO), formatter), equipment, date, work,
                    text(row, cols.get(Col.PARTS), formatter), text(row, cols.get(Col.BY), formatter),
                    text(row, cols.get(Col.NOTES), formatter), problems));
        }
        return out;
    }

    private static String text(Row row, Integer col, DataFormatter formatter) {
        if (col == null) return null;
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        String v = formatter.formatCellValue(cell).trim();
        return v.isEmpty() ? null : v;
    }

    private static LocalDate date(Row row, Integer col, DataFormatter formatter) {
        if (col == null) return null;
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double n = cell.getNumericCellValue();
            // A bare Excel serial date.
            if (n > 20000 && n < 80000) return DateUtil.getLocalDateTime(n).toLocalDate();
        }
        String v = formatter.formatCellValue(cell).trim();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(v, f);
            } catch (DateTimeParseException ignored) {
                // try the next way of writing it
            }
        }
        return null;
    }

    private static String key(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String trim(String v, int max) {
        if (v == null) return null;
        return v.length() > max ? v.substring(0, max) : v;
    }

    record FleetRecordView(Long id, Long vesselId, String vesselName, Long spareId, String sparePath,
                           String spareName, LocalDate serviceDate, String source, String workPerformed,
                           String partsUsed, String performedBy, String requestNumber, String recordedBy,
                           String notes, boolean removable) {}

    record RowView(int rowNumber, String vessel, String equipment, LocalDate date, String work, String parts,
                   String performedBy, String status, String message) {}

    record UploadView(int rowCount, int addCount, int skipCount, int errorCount, boolean applied,
                      List<RowView> rows) {}
}
