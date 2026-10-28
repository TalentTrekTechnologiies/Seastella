package com.seastella.reporting.internal;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SBMC prints the vessel's values on the client's template. What it looks
 * like against the sample is checked by eye and by overlay (the rendered file
 * is left in target/ for that); what it says is checked here.
 */
@DisplayName("the SBMC certificate")
class SbmcRendererTest {

    private final SbmcRenderer renderer = new SbmcRenderer();

    @Test
    @DisplayName("prints every variable field, and the template's fixed text is still there")
    void printsTheVessel() throws Exception {
        byte[] pdf = renderer.render(new SbmcRenderer.Fields("TM-2026-0001", "Ardbeg", "9V2345", "563012345",
                "9301234", "OSM Thome Ship Management Pte Ltd", LocalDate.of(2026, 10, 27), LocalDate.of(2027, 10, 28)));
        Path out = Path.of("target", "sbmc-ardbeg.pdf");
        Files.createDirectories(out.getParent());
        Files.write(out, pdf);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
            assertThat(text)
                    .contains("Certificate No.: TM-2026-0001")
                    .contains("MV. ARDBEG")
                    .contains("(Call Sign: 9V2345; MMSI: 563012345; IMO: 9301234)")
                    .contains("OSM Thome Ship Management Pte Ltd")
                    .contains("This certificate expires: 28th Oct 2027")
                    .contains("Issue Date: 27th Oct 2026")
                    // The client's own wording, untouched.
                    .contains("SHORE BASED MAINTENANCE")
                    .contains("SOLAS Chapter IV, Reg 15")
                    .contains("remotesupport@thawemarine.com")
                    .doesNotContain("xxxx");
        }
    }

    @Test
    @DisplayName("the sample's own placeholders land where the sample put them")
    void placeholdersForOverlay() throws Exception {
        byte[] pdf = renderer.render(new SbmcRenderer.Fields("TM-xxxx-xxxx", "xxxxxxxxxxx", "xxxxx", "xxxxxxxxx",
                "xxxxxxx", "xxxxxxxxxxxxxxxxxxxxxxx", LocalDate.of(2025, 12, 21), LocalDate.of(2026, 12, 22)));
        Path out = Path.of("target", "sbmc-placeholders.pdf");
        Files.createDirectories(out.getParent());
        Files.write(out, pdf);
        assertThat(pdf).isNotEmpty();
    }

    @ParameterizedTest(name = "[{0}] prints [{1}]")
    @CsvSource(delimiter = '|', value = {
            "Ardbeg            | MV. ARDBEG",
            "MV Kestrel Trader | MV. KESTREL TRADER",
            "M.V. Kestrel      | MV. KESTREL",
            "m/v ardbeg        | MV. ARDBEG",
            "MT Bergen Spirit  | MV. MT BERGEN SPIRIT",
    })
    @DisplayName("the template's own \"MV.\" is not doubled")
    void vesselLine(String name, String printed) {
        assertThat(SbmcRenderer.vesselLine(name)).isEqualTo(printed);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"1, st", "2, nd", "3, rd", "4, th", "11, th", "12, th", "13, th", "21, st", "22, nd", "23, rd", "31, st"})
    void ordinals(int day, String suffix) {
        assertThat(SbmcRenderer.ordinal(day)).isEqualTo(suffix);
    }
}
