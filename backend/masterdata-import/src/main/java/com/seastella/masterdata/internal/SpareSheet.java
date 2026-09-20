package com.seastella.masterdata.internal;

import com.seastella.core.api.error.ValidationException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The VMP spreadsheet: its columns, how the template is written, and how an
 * uploaded file is read back (IMP-01 to IMP-04).
 *
 * <p>Columns are matched by heading, not position, so a file with columns moved
 * or extra columns of the ship's own still imports. Headings are compared with
 * spacing, case and punctuation ignored, because a spreadsheet passed between
 * people rarely comes back character-identical.
 *
 * <p>A blank cell means "leave this as it is". That is the safe reading: a
 * partial sheet - one vessel's radars, say - must never wipe details somebody
 * entered by hand. Clearing a value is done in the app, on the spare itself.
 */
final class SpareSheet {

    static final String SHEET_NAME = "Spares";
    static final int MAX_ROWS = 5_000;

    /** Master data only: running hours and working status are recorded on board (IMP-12). */
    enum Column {
        IMO("IMO Number", true),
        VMP_REF("VMP Ref", true),
        CATEGORY("Equipment Category", false),
        NAME("Spare / Description", false),
        MAKE("Make", false),
        MODEL("Model", false),
        SERIAL("Serial Number", false),
        SOFTWARE("Software Version", false),
        INSTALLED("Installation Date", false),
        EXPIRES("Expiry Date", false),
        LAST_SERVICE("Last Annual Service", false),
        LAST_SURVEY("Last Survey", false),
        LAST_APT("Last APT", false),
        HOUR_METER("Has Hour Meter", false),
        CRITICALITY("Criticality", false);

        final String heading;
        final boolean required;

        Column(String heading, boolean required) {
            this.heading = heading;
            this.required = required;
        }
    }

    /** One row as it was read, with the sheet's own row number for messages. */
    record ParsedRow(int rowNumber, Map<Column, String> text, Map<Column, LocalDate> dates) {

        String get(Column c) {
            String v = text.get(c);
            return v == null || v.isBlank() ? null : v.trim();
        }

        LocalDate date(Column c) {
            return dates.get(c);
        }

        boolean isEmpty() {
            return text.values().stream().allMatch(v -> v == null || v.isBlank()) && dates.isEmpty();
        }
    }

    private SpareSheet() {
    }

    // ------------------------------------------------------------- template

    /** The empty template, optionally filled with a vessel's spares for round-trip editing. */
    static byte[] template(List<Map<Column, String>> rows, String vesselLabel) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            instructions(workbook, vesselLabel);
            Sheet sheet = workbook.createSheet(SHEET_NAME);

            CellStyle header = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            header.setFont(bold);
            header.setAlignment(HorizontalAlignment.LEFT);

            Row headings = sheet.createRow(0);
            Column[] columns = Column.values();
            for (int i = 0; i < columns.length; i++) {
                Cell cell = headings.createCell(i);
                cell.setCellValue(columns[i].heading + (columns[i].required ? " *" : ""));
                cell.setCellStyle(header);
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                Map<Column, String> values = rows.get(r);
                for (int i = 0; i < columns.length; i++) {
                    String value = values.get(columns[i]);
                    if (value != null) row.createCell(i).setCellValue(value);
                }
            }
            for (int i = 0; i < columns.length; i++) {
                sheet.setColumnWidth(i, Math.min(60, Math.max(14, columns[i].heading.length() + 4)) * 256);
            }
            sheet.createFreezePane(0, 1);
            dropdown(sheet, Column.CRITICALITY.ordinal(), new String[]{"CRITICAL", "HIGH", "MEDIUM", "LOW"});
            dropdown(sheet, Column.HOUR_METER.ordinal(), new String[]{"Yes", "No"});

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the import template", e);
        }
    }

    private static void instructions(XSSFWorkbook workbook, String vesselLabel) {
        Sheet sheet = workbook.createSheet("How to use this");
        List<String> lines = new ArrayList<>(List.of(
                "SeaStella - vessel master data import",
                "",
                vesselLabel == null
                        ? "Fill in the Spares sheet, one row per spare, then upload it in SeaStella."
                        : "This file holds the spares currently recorded for " + vesselLabel
                                + ". Edit what is wrong, add rows for what is missing, then upload it in SeaStella.",
                "",
                "IMO Number * - identifies the vessel. It must already exist in SeaStella.",
                "VMP Ref *    - the VMP decimal reference, e.g. 13 or 13.1 or 13.1.2. It identifies the spare",
                "               on that vessel. 13.1.2 is recorded under 13.1, so add the parent first.",
                "",
                "A blank cell leaves the value as it is. To clear a value, edit the spare in SeaStella.",
                "Dates are read as dates, or as text in the form 2026-03-31.",
                "Equipment Category and Spare / Description are needed only for a spare SeaStella does not have yet.",
                "Criticality is one of CRITICAL, HIGH, MEDIUM, LOW.",
                "Has Hour Meter is Yes or No; running hours themselves are recorded on board, not imported.",
                "",
                "Nothing changes when you upload. SeaStella shows you what each row would do -",
                "add, change, leave alone, or refuse with the reason - and changes nothing until you confirm."));
        for (int i = 0; i < lines.size(); i++) {
            sheet.createRow(i).createCell(0).setCellValue(lines.get(i));
        }
        sheet.setColumnWidth(0, 110 * 256);
    }

    private static void dropdown(Sheet sheet, int column, String[] options) {
        var helper = sheet.getDataValidationHelper();
        var constraint = helper.createExplicitListConstraint(options);
        var range = new CellRangeAddressList(1, 500, column, column);
        var validation = helper.createValidation(constraint, range);
        validation.setShowErrorBox(true);
        sheet.addValidationData(validation);
    }

    // ---------------------------------------------------------------- parse

    static List<ParsedRow> parse(byte[] content) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = sheet(workbook);
            Row headings = sheet.getRow(sheet.getFirstRowNum());
            if (headings == null) throw new ValidationException("The " + SHEET_NAME + " sheet is empty.");

            Map<Column, Integer> columns = columns(headings);
            for (Column c : Column.values()) {
                if (c.required && !columns.containsKey(c)) {
                    throw new ValidationException("This file has no \"" + c.heading + "\" column. "
                            + "Download the template and fill that in.");
                }
            }

            DataFormatter formatter = new DataFormatter(Locale.UK);
            List<ParsedRow> rows = new ArrayList<>();
            for (int r = headings.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                ParsedRow parsed = read(row, columns, formatter);
                if (parsed.isEmpty()) continue;
                rows.add(parsed);
                if (rows.size() > MAX_ROWS) {
                    throw new ValidationException("This file has more than " + MAX_ROWS
                            + " rows. Split it by vessel and upload each part.");
                }
            }
            if (rows.isEmpty()) throw new ValidationException("This file has no rows to import.");
            return rows;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ValidationException ve) throw ve;
            // A corrupt archive, a .xls saved as .xlsx, a PDF renamed - all land here.
            throw new ValidationException("This file could not be read as an Excel workbook (.xlsx).");
        }
    }

    private static Sheet sheet(Workbook workbook) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (SHEET_NAME.equalsIgnoreCase(workbook.getSheetName(i))) return workbook.getSheetAt(i);
        }
        if (workbook.getNumberOfSheets() == 0) throw new ValidationException("This workbook has no sheets.");
        return workbook.getSheetAt(0);
    }

    private static Map<Column, Integer> columns(Row headings) {
        Map<String, Column> byKey = new HashMap<>();
        for (Column c : Column.values()) {
            byKey.put(key(c.heading), c);
        }
        Map<Column, Integer> found = new EnumMap<>(Column.class);
        DataFormatter formatter = new DataFormatter(Locale.UK);
        for (int i = headings.getFirstCellNum(); i < headings.getLastCellNum(); i++) {
            Cell cell = headings.getCell(i);
            if (cell == null) continue;
            Column column = byKey.get(key(formatter.formatCellValue(cell)));
            if (column != null) found.putIfAbsent(column, i);
        }
        return found;
    }

    /** "Last Annual Service", "last annual service *" and "LastAnnualService" all match. */
    private static String key(String heading) {
        return heading == null ? "" : heading.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static ParsedRow read(Row row, Map<Column, Integer> columns, DataFormatter formatter) {
        Map<Column, String> text = new EnumMap<>(Column.class);
        Map<Column, LocalDate> dates = new EnumMap<>(Column.class);

        columns.forEach((column, index) -> {
            Cell cell = row.getCell(index);
            if (cell == null) return;
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                dates.put(column, cell.getLocalDateTimeCellValue().toLocalDate());
                text.put(column, dates.get(column).toString());
                return;
            }
            String value = formatter.formatCellValue(cell).trim();
            if (value.isEmpty()) return;
            text.put(column, value);
            if (isDateColumn(column)) {
                try {
                    dates.put(column, LocalDate.parse(value));
                } catch (DateTimeParseException ignored) {
                    // Reported per row while previewing, where the row number is known.
                }
            }
        });
        return new ParsedRow(row.getRowNum() + 1, text, dates);
    }

    static boolean isDateColumn(Column column) {
        return column == Column.INSTALLED || column == Column.EXPIRES || column == Column.LAST_SERVICE
                || column == Column.LAST_SURVEY || column == Column.LAST_APT;
    }
}
