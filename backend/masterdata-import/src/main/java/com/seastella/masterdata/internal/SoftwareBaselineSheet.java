package com.seastella.masterdata.internal;

import com.seastella.core.api.error.ValidationException;
import com.seastella.fleet.api.SoftwareBaselineGateway.Baseline;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the client's software master sheet: one row per equipment model,
 * naming the latest release that model should be running.
 *
 * <p>Four columns matter - equipment, make, model, latest version - and only
 * the last two are structurally required, because make and model are what a
 * vessel's equipment is matched on. The sheet is the client's, so the headings
 * are matched by synonym exactly as {@link ClientSheetReader} does, and the
 * heading row is found rather than assumed to be row 1.
 *
 * <p>Like the equipment reader, this decides nothing. It turns a workbook into
 * rows and says which ones it could not use, so a person confirms the change
 * before any vessel's status moves.
 */
final class SoftwareBaselineSheet {

    private static final int HEADER_SEARCH_ROWS = 15;

    /** Cells that mean "nothing here", not a value. */
    private static final Set<String> BLANKS = Set.of("", "-", "--", "na", "n/a", "nil", "none", "tbd", "unknown");

    private SoftwareBaselineSheet() {
    }

    enum Field {
        EQUIPMENT("equipment", "equipmentname", "description", "nameofequipment", "item", "particulars"),
        MAKE("make", "manufacturer", "maker", "brand"),
        MODEL("model", "modeltype", "modelno", "type"),
        LATEST("latestsoftwareversion", "latestversion", "latestsoftware", "currentversion",
                "softwareversion", "latestfirmware", "firmwareversion", "version", "software", "firmware");

        private final Set<String> synonyms;

        Field(String... synonyms) {
            this.synonyms = Set.of(synonyms);
        }

        boolean matches(String key) {
            return synonyms.contains(key);
        }
    }

    /**
     * @param rows     the models the sheet names, ready to write
     * @param warnings rows that were skipped, each saying which and why
     */
    record Parsed(List<Baseline> rows, List<String> warnings) {}

    static Parsed read(byte[] content) throws IOException {
        if (content == null || content.length == 0) {
            throw new ValidationException("The file is empty.");
        }

        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter();
            List<Baseline> rows = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            // The master list is sometimes a tab inside a larger workbook, so
            // every sheet is looked at and the ones without the columns are
            // passed over rather than treated as an error.
            boolean anyHeader = false;
            for (int s = 0; s < book.getNumberOfSheets(); s++) {
                Sheet sheet = book.getSheetAt(s);
                int headerRow = findHeaderRow(sheet, formatter);
                if (headerRow < 0) continue;

                Map<Field, Integer> columns = matchColumns(sheet.getRow(headerRow), formatter);
                if (!columns.containsKey(Field.MODEL) || !columns.containsKey(Field.LATEST)) continue;
                anyHeader = true;

                readRows(sheet, headerRow, columns, formatter, rows, warnings);
            }

            if (!anyHeader) {
                throw new ValidationException(
                        "No sheet in this file has both a model column and a latest-version column. "
                        + "The sheet needs a heading row naming at least Make, Model and Latest software version.");
            }
            if (rows.isEmpty()) {
                throw new ValidationException("The sheet's columns were found, but no row carried both a "
                        + "model and a version.");
            }
            return new Parsed(rows, warnings);

        } catch (IOException | RuntimeException e) {
            if (e instanceof ValidationException v) throw v;
            throw new ValidationException("This file could not be read as a spreadsheet.");
        }
    }

    private static void readRows(Sheet sheet, int headerRow, Map<Field, Integer> columns,
                                 DataFormatter formatter, List<Baseline> rows, List<String> warnings) {
        for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            String equipment = text(row, columns.get(Field.EQUIPMENT), formatter);
            String make = text(row, columns.get(Field.MAKE), formatter);
            String model = text(row, columns.get(Field.MODEL), formatter);
            String latest = text(row, columns.get(Field.LATEST), formatter);

            // A wholly empty row is spacing, not an omission worth reporting.
            if (equipment == null && make == null && model == null && latest == null) continue;

            if (model == null || latest == null) {
                warnings.add("Row " + (r + 1) + " skipped: "
                        + (model == null ? "no model" : "no latest version")
                        + (equipment == null ? "" : " (" + equipment + ")") + ".");
                continue;
            }
            if (make == null) {
                // Without a make there is no match key, so the row cannot be
                // used - and saying so is the whole point of this list.
                warnings.add("Row " + (r + 1) + " skipped: no make, so \"" + model
                        + "\" cannot be matched to equipment.");
                continue;
            }
            rows.add(Baseline.fromSheet(make, model, equipment, latest));
        }
    }

    private static int findHeaderRow(Sheet sheet, DataFormatter formatter) {
        int last = Math.min(sheet.getLastRowNum(), sheet.getFirstRowNum() + HEADER_SEARCH_ROWS);
        for (int r = sheet.getFirstRowNum(); r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Map<Field, Integer> found = matchColumns(row, formatter);
            if (found.containsKey(Field.MODEL) && found.containsKey(Field.LATEST)) return r;
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

    /** @return the cell's text, or null when the cell is empty or says so. */
    private static String text(Row row, Integer column, DataFormatter formatter) {
        if (column == null) return null;
        Cell cell = row.getCell(column);
        if (cell == null) return null;
        String raw = formatter.formatCellValue(cell).trim();
        if (BLANKS.contains(raw.toLowerCase(Locale.ROOT))) return null;
        return raw;
    }

    /** Headings vary in case, spacing and punctuation; the comparison must not. */
    private static String key(String heading) {
        if (heading == null) return "";
        StringBuilder out = new StringBuilder(heading.length());
        for (char c : heading.toCharArray()) {
            if (Character.isLetterOrDigit(c)) out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }
}
