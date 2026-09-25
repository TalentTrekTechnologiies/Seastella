package com.seastella.masterdata.internal;

import com.seastella.masterdata.internal.ClientSheetReader.ClientSheet;
import com.seastella.masterdata.internal.ClientSheetReader.CriticalSpareRow;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A minimum-spares form, in the shape the client's own document uses
 * (GM 2.3.9.9 Navigation Equipment — Minimum Spares Recommendation).
 *
 * <p>Their equipment list carries no spares, and the reader correctly finds
 * none in it. This is the other half of that claim: when a file <em>does</em>
 * hold the minimum-spares table, its rows are read as critical spares and not
 * mistaken for equipment.
 *
 * <p>The sheet is built here rather than checked in, so the test says in code
 * exactly which quirks of their form it is claiming to handle — the equipment
 * named once and left blank for its remaining parts, quantities written as
 * "6 pcs", and a compliance column of Yes / No / NA.
 */
@DisplayName("reading a minimum-spares form")
class CriticalSpareSheetTest {

    @Test
    @DisplayName("spare rows are read as spares, with the equipment they belong to")
    void readsTheMinimumSparesForm() throws IOException {
        ClientSheet sheet = ClientSheetReader.read(minimumSparesForm());

        assertThat(sheet.criticalSpares()).hasSize(5);
        assertThat(sheet.equipment()).as("a spares form is not an equipment list").isEmpty();

        CriticalSpareRow bulbs = spareNamed(sheet, "Bulbs").orElseThrow();
        assertThat(bulbs.equipmentName()).isEqualTo("Magnetic Compass");
        assertThat(bulbs.minimumQuantity()).isEqualTo(2);
        assertThat(bulbs.compliance()).isEqualTo("YES");
    }

    @Test
    @DisplayName("a blank equipment cell means the equipment above it, as the form is written")
    void continuationRowsKeepTheirEquipment() throws IOException {
        ClientSheet sheet = ClientSheetReader.read(minimumSparesForm());

        // Their form names "Magnetic Compass" once and leaves it blank for the
        // correctors on the next line.
        CriticalSpareRow correctors = spareNamed(sheet, "Magnetic Correctors").orElseThrow();
        assertThat(correctors.equipmentName()).isEqualTo("Magnetic Compass");

        CriticalSpareRow carbonBrush = spareNamed(sheet, "Carbon brush").orElseThrow();
        assertThat(carbonBrush.equipmentName()).isEqualTo("Radars");
    }

    @Test
    @DisplayName("a quantity that is not a number is kept as it was written")
    void minimumsThatAreNotNumbersAreKept() throws IOException {
        ClientSheet sheet = ClientSheetReader.read(minimumSparesForm());

        // The form's words are kept as written; the figure they start with is the
        // floor the shortage alert counts against - "2 pcs each ..." is at least 2.
        CriticalSpareRow correctors = spareNamed(sheet, "Magnetic Correctors").orElseThrow();
        assertThat(correctors.minimumQuantity()).isEqualTo(2);
        assertThat(correctors.minimumNote()).contains("athwartship");

        CriticalSpareRow paper = spareNamed(sheet, "Paper Rolls").orElseThrow();
        assertThat(paper.minimumQuantity()).as("\"6 pcs\" counts as 6").isEqualTo(6);
        assertThat(paper.minimumNote()).isEqualTo("6 pcs");
    }

    @Test
    @DisplayName("compliance is read however it was written, and blank stays unassessed")
    void complianceIsNormalised() throws IOException {
        ClientSheet sheet = ClientSheetReader.read(minimumSparesForm());

        assertThat(spareNamed(sheet, "Bulbs").orElseThrow().compliance()).isEqualTo("YES");
        assertThat(spareNamed(sheet, "Magnetron").orElseThrow().compliance()).isEqualTo("NO");
        assertThat(spareNamed(sheet, "Carbon brush").orElseThrow().compliance()).isEqualTo("NA");
        assertThat(spareNamed(sheet, "Paper Rolls").orElseThrow().compliance())
                .as("nobody has assessed it, which is not NA").isNull();

        assertThat(spareNamed(sheet, "Magnetron").orElseThrow().remarks())
                .contains("Requisition");
    }

    // ---------------------------------------------------------------- fixture

    /** The client's form: equipment named once, then its parts beneath it. */
    private byte[] minimumSparesForm() throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Minimum Spares");
            write(sheet, 0, "S.No.", "Equipment", "Spare Part Name", "Minimum Quantity", "Compliance", "Remarks");
            write(sheet, 1, "1", "Magnetic Compass", "Bulbs", "2", "Yes", "");
            write(sheet, 2, "", "", "Magnetic Correctors",
                    "2 pcs each athwartship, fore & aft and Flinders bar", "", "");
            write(sheet, 3, "2", "Navtex Printer", "Paper Rolls", "6 pcs", "", "");
            write(sheet, 4, "3", "Radars", "Magnetron", "1", "No", "Requisition 4471 raised");
            write(sheet, 5, "", "", "Carbon brush", "2", "NA", "X-band only; not fitted");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void write(Sheet sheet, int rowNumber, String... values) {
        Row row = sheet.createRow(rowNumber);
        for (int i = 0; i < values.length; i++) {
            if (!values[i].isEmpty()) row.createCell(i).setCellValue(values[i]);
        }
    }

    private Optional<CriticalSpareRow> spareNamed(ClientSheet sheet, String name) {
        List<CriticalSpareRow> rows = sheet.criticalSpares();
        return rows.stream().filter(r -> name.equalsIgnoreCase(r.partName())).findFirst();
    }
}
