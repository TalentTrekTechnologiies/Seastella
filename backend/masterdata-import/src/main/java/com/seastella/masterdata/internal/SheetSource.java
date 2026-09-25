package com.seastella.masterdata.internal;

import com.seastella.core.api.error.ValidationException;
import com.seastella.masterdata.internal.ClientSheetReader.ClientSheet;
import com.seastella.masterdata.internal.ClientSheetReader.CriticalSpareRow;
import com.seastella.masterdata.internal.ClientSheetReader.EquipmentRow;
import com.seastella.masterdata.internal.ClientSheetReader.VesselDetails;
import com.seastella.masterdata.internal.SpareSheet.Column;
import com.seastella.masterdata.internal.SpareSheet.ParsedRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One uploaded workbook, in the single shape staging works with.
 *
 * <p>Two kinds of file arrive. Our own template is a known shape with columns
 * that exist nowhere else - equipment category, last survey, last APT - and is
 * read exactly. A client's own file is an unknown shape and is read by
 * {@link ClientSheetReader}, which matches headings by meaning.
 *
 * <p>The template is tried first and on its own terms. Reading it through the
 * fuzzy reader would quietly drop the columns that reader has no concept of,
 * which is worse than not accepting the file at all: the import would report
 * success having silently ignored what someone filled in.
 */
record SheetSource(VesselDetails vessel, List<ParsedRow> equipment,
                   List<CriticalSpareRow> criticalSpares, List<String> notes) {

    /** True when the file is ours, and false for anything else we will read leniently. */
    static SheetSource of(byte[] content) {
        if (SpareSheet.isTemplate(content)) {
            return new SheetSource(null, SpareSheet.parse(content), List.of(), List.of());
        }
        ClientSheet sheet = ClientSheetReader.read(content);
        if (sheet.equipment().isEmpty() && sheet.criticalSpares().isEmpty() && sheet.vessel().isEmpty()) {
            throw new ValidationException("Nothing could be read from this file. "
                    + "It needs a heading row naming its columns - an equipment list, a minimum-spares "
                    + "form, or the Thawe Marine template.");
        }
        return new SheetSource(sheet.vessel().isEmpty() ? null : sheet.vessel(),
                asParsedRows(sheet), sheet.criticalSpares(), sheet.notes());
    }

    /**
     * The same file, read as belonging to one chosen vessel - the one whose page
     * it was uploaded from. A client's minimum-spares form often never names the
     * ship; here it does not have to. A file that names a different ship is
     * refused rather than quietly redirected.
     */
    SheetSource intoVessel(String imo, String vesselName) {
        return intoVessel(imo, vesselName, false);
    }

    /**
     * As above; with {@code adopt} the person has already confirmed this file
     * is that vessel's - they added the vessel from it, or said so when told the
     * IMOs differ - and the file's own IMO is set aside rather than refused.
     */
    SheetSource intoVessel(String imo, String vesselName, boolean adopt) {
        String stated = vessel == null ? null : vessel.imoNumber();
        if (!adopt && stated != null && !stated.equals(imo)) {
            throw new ValidationException(mismatch(stated, imo, vesselName));
        }
        List<ParsedRow> rows = new ArrayList<>();
        for (ParsedRow row : equipment) {
            String rowImo = row.get(Column.IMO);
            if (!adopt && rowImo != null && !rowImo.equals(imo)) {
                throw new ValidationException(mismatch(rowImo, imo, vesselName));
            }
            Map<Column, String> text = new LinkedHashMap<>(row.text());
            text.put(Column.IMO, imo);
            rows.add(new ParsedRow(row.rowNumber(), text, row.dates()));
        }
        VesselDetails target = vessel == null
                ? new VesselDetails(imo, null, null, null, null, null, null, null, null)
                : new VesselDetails(imo, vessel.name(), vessel.mmsi(), vessel.callSign(), vessel.flag(),
                        vessel.vesselClass(), vessel.area(), vessel.vesselType(), vessel.dwt());
        return new SheetSource(target, rows, criticalSpares, notes);
    }

    private static String mismatch(String stated, String imo, String vesselName) {
        return "This file is for IMO " + stated + ", not " + vesselName + " (IMO " + imo + "). "
                + "Import it from that vessel's page, or from Data import.";
    }

    /** True when this file is a client's own rather than our template. */
    boolean isClientSheet() {
        return vessel != null || !criticalSpares.isEmpty();
    }

    /**
     * Client equipment in the template's own vocabulary, so the row-by-row
     * checks that have always guarded an import guard these rows too.
     *
     * <p>The vessel's IMO is stamped onto every row. A client file names the
     * ship once, at the top; our template repeats it per row because one file
     * may carry several ships.
     */
    private static List<ParsedRow> asParsedRows(ClientSheet sheet) {
        String imo = sheet.vessel().imoNumber();
        List<ParsedRow> rows = new ArrayList<>();
        for (EquipmentRow row : sheet.equipment()) {
            Map<Column, String> text = new LinkedHashMap<>();
            put(text, Column.IMO, imo);
            put(text, Column.VMP_REF, row.vmpRef());
            put(text, Column.NAME, row.name());
            put(text, Column.MAKE, row.make());
            put(text, Column.MODEL, row.model());
            put(text, Column.SERIAL, row.serial());
            put(text, Column.SOFTWARE, row.software());
            put(text, Column.CRITICALITY, row.criticality());
            // A reading on the sheet is how a client says this equipment is metered.
            // The figure itself stays with the Captain's readings (IMP-12).
            if (isPositive(row.runningHours())) put(text, Column.HOUR_METER, "Yes");

            Map<Column, LocalDate> dates = new LinkedHashMap<>();
            date(dates, text, Column.INSTALLED, row.installed());
            date(dates, text, Column.EXPIRES, row.expires());
            date(dates, text, Column.LAST_SERVICE, row.lastService());

            rows.add(new ParsedRow(row.rowNumber(), text, dates));
        }
        return rows;
    }

    private static void put(Map<Column, String> text, Column column, String value) {
        if (value != null && !value.isBlank()) text.put(column, value.trim());
    }

    /**
     * A date goes in both maps. Staging reads {@code text} to decide whether the
     * cell was filled in at all, and {@code dates} for the value.
     */
    private static void date(Map<Column, LocalDate> dates, Map<Column, String> text,
                             Column column, LocalDate value) {
        if (value == null) return;
        dates.put(column, value);
        text.put(column, value.toString());
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
