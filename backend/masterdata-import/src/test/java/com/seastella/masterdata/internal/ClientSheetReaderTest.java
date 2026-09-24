package com.seastella.masterdata.internal;

import com.seastella.masterdata.internal.ClientSheetReader.ClientSheet;
import com.seastella.masterdata.internal.ClientSheetReader.EquipmentRow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Read against a real client's own equipment list, not a tidy fixture.
 *
 * <p>{@code client-equipment-list.xlsx} is the file a fleet actually sent —
 * AQUA 1 / THAWENAV. It is well kept, and the point of testing against it is
 * that a reader which only copes with our own template is no use to a client
 * migrating forty vessels: their file has to go in as it is.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("reading a client's own equipment list")
class ClientSheetReaderTest {

    private ClientSheet sheet;

    @BeforeAll
    void read() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/client-equipment-list.xlsx")) {
            assertThat(in).as("the sample client file is on the test classpath").isNotNull();
            sheet = ClientSheetReader.read(in.readAllBytes());
        }
    }

    @Test
    @DisplayName("the vessel's own particulars are picked up from the row that carries them")
    void readsTheVessel() {
        // The client fills A to I on the first data row only and leaves them
        // blank down the rest of the sheet.
        assertThat(sheet.vessel().imoNumber()).isEqualTo("9573660");
        assertThat(sheet.vessel().name()).isEqualTo("AQUA 1");
        assertThat(sheet.vessel().mmsi()).isEqualTo("352004089");
        assertThat(sheet.vessel().callSign()).isEqualTo("3E7602");
        assertThat(sheet.vessel().flag()).isEqualTo("PANAMA");
        assertThat(sheet.vessel().vesselClass()).isEqualTo("ABS");
    }

    @Test
    @DisplayName("every equipment row is read, with its VMP number")
    void readsTheEquipment() {
        assertThat(sheet.equipment()).hasSizeGreaterThan(50);
        assertThat(names()).contains("AIS", "VHF NO 1", "X-Band RADAR", "ECDIS NO 1", "GPS 1", "BNWAS");
        assertThat(rowNamed("X-Band RADAR").orElseThrow().vmpRef()).isEqualTo("13.1");
    }

    @Test
    @DisplayName("Excel's mangled decimals become the numbers a person would recognise")
    void decimalsAreNotMangled() {
        // Stored as 2.2000000000000002, 10.199999999999999, 20.100000000000001.
        assertThat(rowNamed("VHF NO 2").orElseThrow().vmpRef()).isEqualTo("2.2");
        assertThat(rowNamed("GMDSS WALKY TALKY NO 2").orElseThrow().vmpRef()).isEqualTo("10.2");

        assertThat(sheet.equipment().stream().map(EquipmentRow::vmpRef).filter(Objects::nonNull))
                .as("no number carries floating-point noise")
                .allMatch(ref -> ref.matches("\\d+(\\.\\d+)*") && ref.length() <= 12);
    }

    @Test
    @DisplayName("the decimal path keeps the nesting the client already has")
    void nestingSurvives() {
        // 13 RADAR -> 13.1 X-Band -> 13.1.2 DISPLAY FAN, three levels, in their file.
        assertThat(refs()).contains("13", "13.1", "13.1.1", "13.1.2", "13.2", "13.2.1");
        assertThat(sheet.equipment().stream()
                .filter(r -> "13.1.4".equals(r.vmpRef()))
                .map(EquipmentRow::name).findFirst())
                .contains("scanner unit FAN");
    }

    @Test
    @DisplayName("dates are read whichever way the client stored them")
    void datesAreRead() {
        EquipmentRow ais = rowNamed("AIS").orElseThrow();
        assertThat(ais.installed()).isNotNull();
        assertThat(ais.installed().getYear()).isEqualTo(2010);
        assertThat(ais.lastService()).isNotNull();
        assertThat(ais.lastService().getYear()).isEqualTo(2025);

        EquipmentRow batteries = rowNamed("EPIRB Batteries").orElseThrow();
        assertThat(batteries.expires()).isNotNull();
        assertThat(batteries.expires().getYear()).isEqualTo(2036);
    }

    @Test
    @DisplayName("NA means empty, not a maker called NA")
    void naIsEmpty() {
        assertThat(rowNamed("NBDP").orElseThrow().make()).isNull();
        EquipmentRow speedlog = rowNamed("Speedlog").orElseThrow();
        assertThat(speedlog.make()).isEqualTo("YOKOGAWA DENSHIKIKI CO. LTD");
        assertThat(speedlog.expires()).as("the expiry cell says NA").isNull();
    }

    @Test
    @DisplayName("a number in the Make column is not a maker")
    void numbersAreNotMakers() {
        // MAGNETRON RUNNING HRS carries 3800 — the hours — in the Make column.
        EquipmentRow magnetron = sheet.equipment().stream()
                .filter(r -> "13.1.1".equals(r.vmpRef())).findFirst().orElseThrow();
        assertThat(magnetron.make()).isNull();
        assertThat(magnetron.warnings()).anyMatch(w -> w.contains("Make column"));
    }

    @Test
    @DisplayName("real values survive intact")
    void realValuesAreKept() {
        EquipmentRow radar = rowNamed("X-Band RADAR").orElseThrow();
        assertThat(radar.make()).isEqualTo("JRC");
        assertThat(radar.model()).isEqualTo("JMA-9122-6XA");
        assertThat(radar.serial()).isEqualTo("LB36406");
        assertThat(radar.lastService()).isNotNull();
    }

    @Test
    @DisplayName("a sheet with no minimum-spares columns yields no critical spares")
    void noSparesInAnEquipmentOnlySheet() {
        // This file is an equipment list; inventing spares from it would be worse
        // than finding none.
        assertThat(sheet.criticalSpares()).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private List<String> names() {
        return sheet.equipment().stream().map(EquipmentRow::name).filter(Objects::nonNull).toList();
    }

    private List<String> refs() {
        return sheet.equipment().stream().map(EquipmentRow::vmpRef).filter(Objects::nonNull).toList();
    }

    private Optional<EquipmentRow> rowNamed(String name) {
        return sheet.equipment().stream()
                .filter(r -> r.name() != null && name.equalsIgnoreCase(r.name().trim()))
                .findFirst();
    }
}
