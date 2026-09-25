package com.seastella.masterdata.internal;

import com.seastella.core.api.error.ValidationException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads a fleet's own spreadsheet, whatever shape it arrives in.
 *
 * <p>A client joining the platform sends the file they already keep, not the
 * template we would like. One workbook may carry the vessel's particulars, its
 * equipment list and its minimum-spares form all together — the real file this
 * was built against (AQUA 1 / THAWENAV) puts the vessel across columns A to I
 * of the first data row and the equipment from J onwards. So this does not ask
 * what kind of file it is: it works out which columns are present, and sorts
 * each row into the vessel, its equipment, or its critical spares.
 *
 * <p>What it copes with, because real files do these things:
 * <ul>
 *   <li>The heading row is not always row 1, so it is found rather than assumed.</li>
 *   <li>Headings vary — "Description", "Equipment", "Spare / Description" all
 *       name the same column, and so do "Make" and "Manufacturer".</li>
 *   <li>Excel stores 2.2 as 2.2000000000000002. Read as text that would create
 *       equipment numbered 2.2000000000000002.</li>
 *   <li>Dates arrive as date cells, as bare serial numbers, or as text in half
 *       a dozen shapes — and sometimes as something that is not a date at all.</li>
 *   <li>"NA", "N/A" and "-" mean empty. They are not a maker called NA.
 *       The exception is compliance, where "N/A" is an answer someone
 *       gave and an empty cell is one nobody gave.</li>
 * </ul>
 *
 * <p>Nothing here decides what to <em>do</em> with a row: that is the preview's
 * job. This turns a spreadsheet into records and says plainly what it could not
 * read, so a person can judge it before anything changes.
 */
final class ClientSheetReader {

    private ClientSheetReader() {
    }

    /** How far down to look for the heading row before giving up. */
    private static final int HEADER_SEARCH_ROWS = 15;

    /** Outside this range it is not a date; it is a typo or another column's data. */
    private static final LocalDate EARLIEST_CREDIBLE = LocalDate.of(1970, 1, 1);

    private static final Set<String> BLANKS =
            Set.of("na", "n/a", "nil", "none", "-", "--", "n.a.", "tba", "na.", "x");

    private static final List<DateTimeFormatter> TEXT_DATES = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.UK),
            DateTimeFormatter.ofPattern("d/M/yyyy", Locale.UK),
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.UK),
            DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.UK),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.UK));

    /**
     * Every column this reader knows how to recognise, and the headings a
     * client might use for it. Matching is on letters and digits only, so
     * "Date of Installation (DD-MM-YYYY)" and "installation_date" both land.
     */
    enum Field {
        // --- the vessel ---
        VESSEL_NAME("vesselname", "nameofvessel", "shipname", "vessel"),
        IMO("imonumber", "imo", "imono", "vesselimo"),
        MMSI("vesselmmsi", "mmsi"),
        CALL_SIGN("vesselcallsign", "callsign"),
        FLAG("vesselflag", "flag"),
        VESSEL_CLASS("vesselclass", "class", "classsociety"),
        AREA("vesselarea", "area", "tradingarea"),
        VESSEL_TYPE("vesseltype", "typeofvessel"),
        DWT("dwt", "deadweight"),

        // --- equipment ---
        VMP_REF("id", "sno", "srno", "slno", "itemno", "vmpref", "vmp", "ref"),
        NAME("description", "equipmentname", "equipment", "sparedescription", "item",
                "itemdescription", "particulars", "nameofequipment"),
        MAKE("make", "manufacturer", "maker", "brand"),
        MODEL("model", "modeltype"),
        SERIAL("serialnumber", "serialno", "serial"),
        SOFTWARE("sofwareversion", "softwareversion", "software", "firmware", "version"),
        INSTALLED("dateofinstallation", "installationdate", "installed", "fitteddate"),
        EXPIRES("expirationdates", "expirationdate", "expirydate", "expiry", "expires"),
        LAST_SERVICE("lastannualservice", "lastservice", "lastdone", "lastannualservicesurveyapt"),
        RUNNING_HOURS("runninghours", "hoursrun", "hourmeter"),
        CRITICALITY("criticality"),

        // --- critical spares (the minimum-spares form) ---
        SPARE_PART("sparepartname", "sparepart", "partname", "spares", "spareparts"),
        MINIMUM_QTY("minimumquantity", "minimumqty", "minqty", "minimum", "requiredquantity", "reqqty"),
        ON_BOARD("quantityonhand", "qtyonboard", "onboard", "actualquantity", "actualqty", "stock",
                "availablequantity"),
        COMPLIANCE("compliance", "complied"),
        REMARKS("remarks", "remark", "comments", "observation");

        private final List<String> keys;

        Field(String... keys) {
            this.keys = Arrays.asList(keys);
        }

        boolean matches(String key) {
            return keys.stream().anyMatch(k -> key.equals(k) || key.startsWith(k));
        }
    }

    /** The vessel's own particulars, wherever in the sheet they were found. */
    record VesselDetails(String imoNumber, String name, String mmsi, String callSign, String flag,
                         String vesselClass, String area, String vesselType, String dwt) {

        boolean isEmpty() {
            return imoNumber == null && name == null;
        }
    }

    /** One piece of equipment, keyed by its VMP number. */
    record EquipmentRow(int rowNumber, String vmpRef, String name, String make, String model, String serial,
                        String software, LocalDate installed, LocalDate expires, LocalDate lastService,
                        BigDecimal runningHours, String criticality, List<String> warnings) {}

    /** One line of the minimum-spares form. */
    record CriticalSpareRow(int rowNumber, String equipmentRef, String equipmentName, String partName,
                            Integer minimumQuantity, String minimumNote, Integer quantityOnHand,
                            String compliance, String remarks, List<String> warnings) {}

    /** Everything one workbook turned out to contain. */
    record ClientSheet(VesselDetails vessel, List<EquipmentRow> equipment,
                       List<CriticalSpareRow> criticalSpares, List<String> notes) {}

    // ------------------------------------------------------------------ read

    static ClientSheet read(byte[] content) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            if (workbook.getNumberOfSheets() == 0) throw new ValidationException("This workbook has no sheets.");

            DataFormatter formatter = new DataFormatter(Locale.UK);
            List<String> notes = new ArrayList<>();
            List<EquipmentRow> equipment = new ArrayList<>();
            List<CriticalSpareRow> spares = new ArrayList<>();
            VesselDetails vessel = null;

            // Every sheet is read: a workbook often keeps the equipment on one
            // tab and the minimum spares on another.
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                int headerRow = findHeaderRow(sheet, formatter);
                if (headerRow < 0) continue;

                Map<Field, Integer> columns = matchColumns(sheet.getRow(headerRow), formatter);
                if (columns.size() < 2) continue;

                String label = workbook.getNumberOfSheets() == 1 ? "" : "\"" + sheet.getSheetName() + "\": ";
                VesselDetails found = readVessel(sheet, headerRow, columns, formatter);
                if (vessel == null && !found.isEmpty()) vessel = found;

                int before = equipment.size() + spares.size();
                readRows(sheet, headerRow, columns, formatter, equipment, spares);
                int read = equipment.size() + spares.size() - before;
                if (read > 0) notes.add(label + read + " row" + (read == 1 ? "" : "s") + " read.");
            }

            if (equipment.isEmpty() && spares.isEmpty()) {
                throw new ValidationException("No equipment or spare rows were found. The sheet needs a heading "
                        + "row naming its columns — a Description, Equipment or Spare Part Name, and ideally an ID "
                        + "or S.No. Or download the SeaStella template, which has an IMO Number and VMP Ref column "
                        + "and is always accepted.");
            }
            if (equipment.size() + spares.size() > SpareSheet.MAX_ROWS) {
                throw new ValidationException("This file has more than " + SpareSheet.MAX_ROWS
                        + " rows. Split it by vessel and upload each part.");
            }
            return new ClientSheet(vessel == null ? empty() : vessel, equipment, spares, notes);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ValidationException ve) throw ve;
            throw new ValidationException("This file could not be read. It needs to be an Excel workbook "
                    + "(.xlsx) with a heading row naming its columns.");
        }
    }

    private static VesselDetails empty() {
        return new VesselDetails(null, null, null, null, null, null, null, null, null);
    }

    // -------------------------------------------------------------- headings

    /** The first row that names at least two columns we recognise. */
    private static int findHeaderRow(Sheet sheet, DataFormatter formatter) {
        int last = Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + HEADER_SEARCH_ROWS);
        for (int r = sheet.getFirstRowNum(); r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            if (matchColumns(row, formatter).size() >= 2) return r;
        }
        return -1;
    }

    private static Map<Field, Integer> matchColumns(Row headings, DataFormatter formatter) {
        Map<Field, Integer> found = new EnumMap<>(Field.class);
        if (headings == null) return found;
        for (int i = Math.max(0, headings.getFirstCellNum()); i < headings.getLastCellNum(); i++) {
            Cell cell = headings.getCell(i);
            if (cell == null) continue;
            String key = key(formatter.formatCellValue(cell));
            if (key.isEmpty()) continue;
            for (Field field : Field.values()) {
                if (found.containsKey(field)) continue;
                if (field.matches(key)) {
                    found.put(field, i);
                    break;
                }
            }
        }
        return found;
    }

    // ----------------------------------------------------------------- rows

    /**
     * The vessel's particulars, which a client's sheet usually fills in on the
     * first data row only and leaves blank thereafter.
     */
    private static VesselDetails readVessel(Sheet sheet, int headerRow, Map<Field, Integer> columns,
                                            DataFormatter formatter) {
        return new VesselDetails(
                firstValue(sheet, headerRow, columns.get(Field.IMO), formatter),
                firstValue(sheet, headerRow, columns.get(Field.VESSEL_NAME), formatter),
                firstValue(sheet, headerRow, columns.get(Field.MMSI), formatter),
                firstValue(sheet, headerRow, columns.get(Field.CALL_SIGN), formatter),
                firstValue(sheet, headerRow, columns.get(Field.FLAG), formatter),
                firstValue(sheet, headerRow, columns.get(Field.VESSEL_CLASS), formatter),
                firstValue(sheet, headerRow, columns.get(Field.AREA), formatter),
                firstValue(sheet, headerRow, columns.get(Field.VESSEL_TYPE), formatter),
                firstValue(sheet, headerRow, columns.get(Field.DWT), formatter));
    }

    /**
     * Sorts each row into equipment or a critical spare.
     *
     * <p>A row is a critical spare when it names a spare part or states a
     * minimum to hold; otherwise, if it has a number and a description, it is
     * equipment. A sheet that mixes both — equipment down the page with the
     * minimum spares against some of them — sorts itself out row by row.
     */
    private static void readRows(Sheet sheet, int headerRow, Map<Field, Integer> columns, DataFormatter formatter,
                                 List<EquipmentRow> equipment, List<CriticalSpareRow> spares) {
        String lastEquipmentRef = null;
        String lastEquipmentName = null;

        for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            List<String> warnings = new ArrayList<>();
            String partName = text(row, columns.get(Field.SPARE_PART), formatter);
            Integer minimum = wholeNumber(row, columns.get(Field.MINIMUM_QTY), formatter);
            String minimumNote = minimum == null ? text(row, columns.get(Field.MINIMUM_QTY), formatter) : null;
            // "2 nos." still means at least two: the words stay as the note, and the
            // leading figure drives the below-minimum alert.
            if (minimum == null) minimum = leadingCount(minimumNote);

            String vmpRef = vmpRef(cell(row, columns.get(Field.VMP_REF)), formatter);
            String name = text(row, columns.get(Field.NAME), formatter);

            boolean isSpare = partName != null || minimum != null || minimumNote != null;
            if (isSpare) {
                // The form repeats the equipment only on its first line, so a
                // blank equipment cell means "the one above".
                String equipmentRef = vmpRef != null ? vmpRef : lastEquipmentRef;
                String equipmentName = name != null ? name : lastEquipmentName;
                if (name != null) {
                    lastEquipmentName = name;
                    lastEquipmentRef = vmpRef;
                }
                spares.add(new CriticalSpareRow(r + 1, equipmentRef, equipmentName,
                        partName != null ? partName : name,
                        minimum, minimumNote,
                        wholeNumber(row, columns.get(Field.ON_BOARD), formatter),
                        compliance(rawText(row, columns.get(Field.COMPLIANCE), formatter)),
                        text(row, columns.get(Field.REMARKS), formatter),
                        warnings));
                continue;
            }

            if (vmpRef == null && name == null) continue;

            String make = text(row, columns.get(Field.MAKE), formatter);
            if (make != null && make.matches("\\d+(\\.\\d+)?")) {
                warnings.add("\"" + make + "\" in the Make column is a number, not a maker. It was left out.");
                make = null;
            }

            equipment.add(new EquipmentRow(r + 1, vmpRef, name, make,
                    text(row, columns.get(Field.MODEL), formatter),
                    text(row, columns.get(Field.SERIAL), formatter),
                    text(row, columns.get(Field.SOFTWARE), formatter),
                    date(row, columns.get(Field.INSTALLED), formatter, "installation date", warnings),
                    date(row, columns.get(Field.EXPIRES), formatter, "expiry date", warnings),
                    date(row, columns.get(Field.LAST_SERVICE), formatter, "last service date", warnings),
                    decimal(row, columns.get(Field.RUNNING_HOURS), formatter),
                    text(row, columns.get(Field.CRITICALITY), formatter),
                    warnings));
            lastEquipmentRef = vmpRef;
            lastEquipmentName = name;
        }
    }

    // -------------------------------------------------------------- reading

    /**
     * A VMP number, if this cell holds one.
     *
     * <p>Taken from the cell's own number when it is numeric, so Excel's
     * 2.2000000000000002 becomes 2.2 rather than a path nobody would recognise.
     */
    static String vmpRef(Cell cell, DataFormatter formatter) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell)) {
            BigDecimal value = BigDecimal.valueOf(cell.getNumericCellValue())
                    .setScale(4, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
            return value.toPlainString();
        }
        String raw = formatter.formatCellValue(cell).trim();
        if (raw.isEmpty() || isBlankWord(raw)) return null;
        // 13, 13.1, 13.1.2 — and nothing else.
        return raw.matches("\\d+(\\.\\d+)*") ? raw : null;
    }

    /**
     * The cell in a column, or nothing when the sheet has no such column.
     *
     * <p>A minimum-spares form often has no ID column at all, and POI refuses a
     * negative index rather than returning nothing, so asking for a column that
     * was never matched has to be answered here.
     */
    private static Cell cell(Row row, Integer column) {
        return column == null || column < 0 ? null : row.getCell(column);
    }

    private static String text(Row row, Integer column, DataFormatter formatter) {
        if (column == null || column < 0) return null;
        Cell cell = row.getCell(column);
        if (cell == null) return null;
        String value = formatter.formatCellValue(cell).trim();
        return value.isEmpty() || isBlankWord(value) ? null : value;
    }

    /**
     * A cell read without the "NA means empty" rule.
     *
     * <p>For a maker or an expiry date, "NA" is how people write an empty cell.
     * In a compliance column it is not: "N/A" says this spare does not apply to
     * this vessel, which is an assessment, and a cell nobody filled in is not.
     * Collapsing the two would report an unanswered form as answered.
     */
    private static String rawText(Row row, Integer column, DataFormatter formatter) {
        if (column == null || column < 0) return null;
        Cell cell = row.getCell(column);
        if (cell == null) return null;
        String value = formatter.formatCellValue(cell).trim();
        return value.isEmpty() ? null : value;
    }

    private static final java.util.regex.Pattern LEADING_COUNT = java.util.regex.Pattern.compile("^(\\d{1,5})(?!\\s*[./:-]\\s*\\d)\\b");

    /** The figure a quantity starts with - 2 from "2 nos." or "2 pcs each ..." - or null. */
    static Integer leadingCount(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = LEADING_COUNT.matcher(text.trim());
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /** "6 pcs" is not a whole number; "6" is. A quantity we cannot count stays text. */
    private static Integer wholeNumber(Row row, Integer column, DataFormatter formatter) {
        String value = text(row, column, formatter);
        if (value == null) return null;
        try {
            return Integer.valueOf(new BigDecimal(value).setScale(0, RoundingMode.HALF_UP).intValueExact());
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimal(Row row, Integer column, DataFormatter formatter) {
        String value = text(row, column, formatter);
        if (value == null) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String compliance(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (value.startsWith("Y")) return "YES";
        if (value.startsWith("N") && !value.startsWith("NA") && !value.startsWith("N/A")) return "NO";
        if (value.startsWith("NA") || value.startsWith("N/A")) return "NA";
        return null;
    }

    /** A date, however it was stored — and nothing when what is there is not credible as one. */
    private static LocalDate date(Row row, Integer column, DataFormatter formatter, String what,
                                  List<String> warnings) {
        if (column == null || column < 0) return null;
        Cell cell = row.getCell(column);
        if (cell == null) return null;

        if (cell.getCellType() == CellType.NUMERIC) {
            double raw = cell.getNumericCellValue();
            if (DateUtil.isCellDateFormatted(cell)) {
                return credible(cell.getLocalDateTimeCellValue().toLocalDate(), raw, what, warnings);
            }
            // A bare number in a date column is an Excel serial — or a typo.
            if (raw > 0 && raw < 2_958_466) {
                LocalDate local = DateUtil.getJavaDate(raw).toInstant().atZone(ZoneOffset.UTC).toLocalDate();
                return credible(local, raw, what, warnings);
            }
            return null;
        }

        String value = formatter.formatCellValue(cell).trim();
        if (value.isEmpty() || isBlankWord(value)) return null;
        for (DateTimeFormatter pattern : TEXT_DATES) {
            try {
                return LocalDate.parse(value, pattern);
            } catch (DateTimeParseException ignored) {
                // Try the next shape.
            }
        }
        warnings.add("\"" + value + "\" is not a date the " + what + " could be read from.");
        return null;
    }

    /**
     * A serial of 99 is the 9th of April 1900. Nobody meant that, and importing
     * it would put equipment into service before the war.
     */
    private static LocalDate credible(LocalDate date, double raw, String what, List<String> warnings) {
        if (date.isBefore(EARLIEST_CREDIBLE) || date.isAfter(LocalDate.now().plusYears(50))) {
            warnings.add("The " + what + " reads " + trimNumber(raw) + ", which is not a real date. It was left out.");
            return null;
        }
        return date;
    }

    // -------------------------------------------------------------- helpers

    private static String firstValue(Sheet sheet, int headerRow, Integer column, DataFormatter formatter) {
        if (column == null || column < 0) return null;
        for (int r = headerRow + 1; r <= Math.min(sheet.getLastRowNum(), headerRow + 30); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String value = text(row, column, formatter);
            if (value != null) return value;
        }
        return null;
    }

    private static boolean isBlankWord(String value) {
        return BLANKS.contains(value.toLowerCase(Locale.ROOT));
    }

    private static String trimNumber(double raw) {
        return raw == Math.rint(raw) ? String.valueOf((long) raw) : String.valueOf(raw);
    }

    private static String key(String heading) {
        return heading == null ? "" : heading.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
