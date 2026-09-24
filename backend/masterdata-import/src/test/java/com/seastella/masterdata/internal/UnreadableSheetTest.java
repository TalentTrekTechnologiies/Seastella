package com.seastella.masterdata.internal;

import com.seastella.core.api.error.ValidationException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The edges of reading a stranger's file: what is accepted without an ID
 * column, and what a file we cannot use at all is told.
 *
 * <p>Being lenient about shape is not the same as being vague about failure. A
 * workbook that opened but was understood not at all has to say which columns
 * it needed, and offer the template as the way out.
 */
@DisplayName("the edges of reading a client's file")
class UnreadableSheetTest {

    @Test
    @DisplayName("a spares form with no ID column is read, not rejected")
    void noIdColumnIsFine() throws IOException {
        // Their minimum-spares form often has no serial column at all. POI
        // refuses a negative cell index, so a missing column used to surface as
        // "this is not an Excel file" - which it plainly was.
        byte[] file = workbook(sheet -> {
            write(sheet, 0, "Equipment", "Spare Part Name", "Minimum Quantity", "Compliance");
            write(sheet, 1, "Magnetic Compass", "Bulbs", "2", "Yes");
            write(sheet, 2, "", "Magnetic Correctors", "4", "No");
        });

        ClientSheetReader.ClientSheet sheet = ClientSheetReader.read(file);

        assertThat(sheet.criticalSpares()).hasSize(2);
        assertThat(sheet.criticalSpares().get(0).partName()).isEqualTo("Bulbs");
        assertThat(sheet.criticalSpares().get(0).equipmentName()).isEqualTo("Magnetic Compass");
        assertThat(sheet.criticalSpares().get(1).equipmentName())
                .as("a blank equipment cell still means the line above")
                .isEqualTo("Magnetic Compass");
        assertThat(sheet.criticalSpares().get(0).equipmentRef())
                .as("there is no reference to have")
                .isNull();
    }

    @Test
    @DisplayName("an equipment list with no ID column is read by name alone")
    void equipmentWithoutReferences() throws IOException {
        byte[] file = workbook(sheet -> {
            write(sheet, 0, "IMO Number", "Equipment Name", "Make", "Model");
            write(sheet, 1, "9573660", "AIS", "Furuno", "FA-170");
            write(sheet, 2, "", "BNWAS", "Daiwa", "DB-100");
        });

        ClientSheetReader.ClientSheet sheet = ClientSheetReader.read(file);

        assertThat(sheet.vessel().imoNumber()).isEqualTo("9573660");
        assertThat(sheet.equipment()).hasSize(2);
        assertThat(sheet.equipment().get(0).name()).isEqualTo("AIS");
        assertThat(sheet.equipment().get(0).vmpRef()).isNull();
    }

    @Test
    @DisplayName("columns that mean nothing to us are refused, naming what was needed")
    void unrecognisedColumnsAreRefused() throws IOException {
        byte[] file = workbook(sheet -> {
            write(sheet, 0, "Vessel", "Item");
            write(sheet, 1, "MV Something");
        });

        assertThatThrownBy(() -> ClientSheetReader.read(file))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("No equipment or spare rows were found")
                .hasMessageContaining("template");
    }

    @Test
    @DisplayName("a file that is not a workbook at all says so")
    void realFormatProblemsAreStillReported() {
        assertThatThrownBy(() -> ClientSheetReader.read("IMO,VMP\n123,13.1".getBytes()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining(".xlsx");
    }

    @Test
    @DisplayName("our own template is recognised as ours and is not read leniently")
    void theTemplateIsRecognised() throws IOException {
        assertThat(SpareSheet.isTemplate(workbook(sheet -> write(sheet, 0, "IMO Number", "VMP Ref")))).isTrue();
        assertThat(SpareSheet.isTemplate(workbook(sheet -> write(sheet, 0, "Vessel", "Item")))).isFalse();
        assertThat(SpareSheet.isTemplate("not a workbook".getBytes()))
                .as("a question about shape is not a place to throw")
                .isFalse();
    }

    // ---------------------------------------------------------------- fixture

    private byte[] workbook(java.util.function.Consumer<Sheet> build) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            build.accept(workbook.createSheet("Sheet1"));
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static void write(Sheet sheet, int rowNumber, String... values) {
        Row row = sheet.createRow(rowNumber);
        for (int i = 0; i < values.length; i++) {
            if (!values[i].isEmpty()) row.createCell(i).setCellValue(values[i]);
        }
    }
}
