package com.seastella.masterdata.internal;

import com.seastella.core.api.error.ValidationException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading the client's software master sheet.
 *
 * <p>The sheet is theirs, so the tests are about the shapes a real one takes:
 * headings worded differently, a title above the headings, the list living on
 * a later tab, and rows that cannot be used. The last of those matters most —
 * a row silently dropped becomes a model that reports "not known" forever, and
 * nobody goes looking for the row that never arrived.
 */
@DisplayName("reading the software master sheet")
class SoftwareBaselineSheetTest {

    @Test
    @DisplayName("the four columns are read, whatever the headings are called")
    void readsTheColumns() throws IOException {
        byte[] file = workbook(sheet -> {
            write(sheet, 0, "Equipment", "Manufacturer", "Model", "Latest Software Version");
            write(sheet, 1, "AIS", "Furuno", "FA-170", "5.6");
            write(sheet, 2, "ECDIS", "JRC", "JAN-9201", "2.1.0");
        });

        SoftwareBaselineSheet.Parsed parsed = SoftwareBaselineSheet.read(file);

        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).make()).isEqualTo("Furuno");
        assertThat(parsed.rows().get(0).model()).isEqualTo("FA-170");
        assertThat(parsed.rows().get(0).equipmentName()).isEqualTo("AIS");
        assertThat(parsed.rows().get(0).latestVersion()).isEqualTo("5.6");
        assertThat(parsed.warnings()).isEmpty();
    }

    @Test
    @DisplayName("a title above the headings does not hide them")
    void findsTheHeadingRow() throws IOException {
        byte[] file = workbook(sheet -> {
            write(sheet, 0, "NAVIGATION EQUIPMENT — SOFTWARE VERSIONS");
            write(sheet, 1, "Updated March 2026");
            write(sheet, 3, "Make", "Model", "Latest version");
            write(sheet, 4, "Furuno", "FA-170", "5.6");
        });

        assertThat(SoftwareBaselineSheet.read(file).rows()).hasSize(1);
    }

    @Test
    @DisplayName("the list is found on whichever tab carries it")
    void findsTheRightTab() throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            write(book.createSheet("Notes"), 0, "Prepared by", "Fleet Technical");
            Sheet list = book.createSheet("Software");
            write(list, 0, "Make", "Model", "Latest version");
            write(list, 1, "Sperry", "VISIONMASTER", "10.2");
            book.write(out);

            assertThat(SoftwareBaselineSheet.read(out.toByteArray()).rows()).hasSize(1);
        }
    }

    @Test
    @DisplayName("a row with no make is skipped and said so, because it could not be matched")
    void rowWithoutMakeIsReported() throws IOException {
        byte[] file = workbook(sheet -> {
            write(sheet, 0, "Make", "Model", "Latest version");
            write(sheet, 1, "Furuno", "FA-170", "5.6");
            write(sheet, 2, "", "JAN-9201", "2.1.0");
        });

        SoftwareBaselineSheet.Parsed parsed = SoftwareBaselineSheet.read(file);

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.warnings()).hasSize(1);
        assertThat(parsed.warnings().get(0)).contains("JAN-9201").contains("no make");
    }

    @Test
    @DisplayName("a row with no version is skipped and said so")
    void rowWithoutVersionIsReported() throws IOException {
        byte[] file = workbook(sheet -> {
            write(sheet, 0, "Make", "Model", "Latest version");
            write(sheet, 1, "Furuno", "FA-170", "");
            write(sheet, 2, "JRC", "JAN-9201", "2.1.0");
        });

        SoftwareBaselineSheet.Parsed parsed = SoftwareBaselineSheet.read(file);

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.warnings()).hasSize(1);
        assertThat(parsed.warnings().get(0)).contains("no latest version");
    }

    @Test
    @DisplayName("\"N/A\" and a dash mean empty, not a maker called NA")
    void blankMarkersAreEmpty() throws IOException {
        byte[] file = workbook(sheet -> {
            write(sheet, 0, "Equipment", "Make", "Model", "Latest version");
            write(sheet, 1, "-", "Furuno", "FA-170", "5.6");
            write(sheet, 2, "GPS", "N/A", "GP-170", "1.2");
        });

        SoftwareBaselineSheet.Parsed parsed = SoftwareBaselineSheet.read(file);

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().get(0).equipmentName())
                .as("a dash in the equipment column is not a name")
                .isNull();
        assertThat(parsed.warnings().get(0)).contains("GP-170");
    }

    @Test
    @DisplayName("blank spacer rows are not reported as problems")
    void blankRowsAreIgnored() throws IOException {
        byte[] file = workbook(sheet -> {
            write(sheet, 0, "Make", "Model", "Latest version");
            write(sheet, 1, "Furuno", "FA-170", "5.6");
            write(sheet, 2, "");
            write(sheet, 3, "JRC", "JAN-9201", "2.1.0");
        });

        SoftwareBaselineSheet.Parsed parsed = SoftwareBaselineSheet.read(file);

        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.warnings()).isEmpty();
    }

    @Test
    @DisplayName("a sheet without the columns says which ones it needed")
    void missingColumnsAreExplained() throws IOException {
        byte[] file = workbook(sheet -> {
            write(sheet, 0, "Vessel", "Remarks");
            write(sheet, 1, "MV Something", "nothing to report");
        });

        assertThatThrownBy(() -> SoftwareBaselineSheet.read(file))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Model")
                .hasMessageContaining("Latest software version");
    }

    @Test
    @DisplayName("a file that is not a workbook says so rather than throwing something raw")
    void notAWorkbook() {
        assertThatThrownBy(() -> SoftwareBaselineSheet.read("make,model,version".getBytes()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("an empty upload is refused")
    void emptyUpload() {
        assertThatThrownBy(() -> SoftwareBaselineSheet.read(new byte[0]))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("empty");
    }

    // ---------------------------------------------------------------- fixture

    private byte[] workbook(Consumer<Sheet> build) throws IOException {
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
