package com.seastella.reporting.internal;

import com.seastella.fleet.api.VesselRecords.EquipmentRecord;
import com.seastella.fleet.api.VesselRecords.VesselParticulars;
import com.seastella.maintenance.api.DueStatus;
import com.seastella.maintenance.api.MaintenanceMetrics.DueItem;
import com.seastella.servicerequest.api.ServiceRequestMetrics.RequestSummary;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The QM-F11 form filled from a vessel's register - the brief's own ARDBEG
 * example, numbered the way the client's sheet numbers it (VDR at 11, its
 * batteries at 11.1-11.4), and a standard-fit vessel numbered the platform's
 * way (VDR at 11.1). Both must read the same.
 */
@DisplayName("the QM-F11 pre-survey form")
class PreSurveyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    /** Full dates, as the client chose (28 Oct 2026). */
    private static final DateTimeFormatter DATES = DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH);
    private static final VesselParticulars ARDBEG = new VesselParticulars(5L, 3L, "OSM Thome", "ARDBEG", "9301234",
            "563012345", "9V2345", "Singapore", "DNV", "Worldwide", "Bulk carrier", null, "ACTIVE", null, null, null);

    private static long id = 100;

    private static EquipmentRecord item(String path, String name, String make, String model, LocalDate expiry) {
        return new EquipmentRecord(id++, path, name, make, model, null, expiry, null, null, null, null);
    }

    private static EquipmentRecord hours(String path, String name, int h) {
        return new EquipmentRecord(id++, path, name, null, null, null, null, null, BigDecimal.valueOf(h), null, null);
    }

    /** ARDBEG as the brief describes it (page 2), as imported from the client's sheet. */
    static List<EquipmentRecord> ardbeg() {
        List<EquipmentRecord> r = new ArrayList<>();
        r.add(item("8", "EPIRB", null, null, null));
        r.add(item("8.1", "EPIRB No 1", "JOTRON", "TRON 60AIS", null));
        r.add(item("8.1.1", "EPIRB No 1 Battery", null, null, LocalDate.of(2036, 4, 30)));
        r.add(item("8.1.2", "EPIRB No 1 Hydrostatic (HRU)", null, null, LocalDate.of(2027, 5, 30)));
        r.add(item("8.2", "EPIRB No 2", null, null, null));   // empty on ARDBEG: prints blank
        r.add(item("9.1", "SART 1", "JOTRON", "TRON SART 20", LocalDate.of(2030, 5, 30)));
        r.add(item("9.2", "SART 2", "JOTRON", "TRON SART 20", LocalDate.of(2030, 5, 30)));
        r.add(item("10", "GMDSS WALKY TALKY", null, null, null));
        r.add(item("10.1", "GMDSS WALKY TALKY NO 1", "JOTRON", "TRON TR 30", null));
        r.add(item("10.2", "GMDSS WALKY TALKY NO 2", "JOTRON", "TRON TR 30", null));
        r.add(item("10.3", "GMDSS WALKY TALKY NO 3", "JOTRON", "TRON TR 30", null));
        r.add(item("11", "VDR / SVDR", "JRC", "JCY 1900", null));
        r.add(item("11.1", "VDR backup batteries (PSU)", null, null, LocalDate.of(2029, 2, 28)));
        r.add(item("11.2", "VDR acoustic beacon", null, null, LocalDate.of(2027, 8, 30)));
        r.add(item("11.3", "VDR FFC battery", null, null, LocalDate.of(2030, 5, 30)));
        r.add(item("11.4", "FFC HRU", null, null, LocalDate.of(2027, 5, 30)));
        r.add(item("12.1", "Gyro No 1", "TOKYO KEIKI", "TG 8100", null));
        r.add(item("13.1", "X-Band Radar", "JRC", "JM-9225-9X", null));
        r.add(hours("13.1.1", "X-Band Magnetron", 323));
        r.add(item("13.2", "S-Band Radar", "JRC", "JMR-9282-S", null));
        r.add(hours("13.2.1", "S-Band Magnetron", 6149));
        r.add(item("14.1", "ECDIS No 1", "JRC", "9201", null));
        r.add(new EquipmentRecord(id++, "14.1.3", "ECDIS No 1 SSD Unit", null, null, null, null, null,
                BigDecimal.valueOf(9495), LocalDate.of(2025, 3, 14), null));
        return r;
    }

    private static Map<String, String> fill(List<EquipmentRecord> register, List<DueItem> overdue,
                                            List<RequestSummary> open) {
        return new PreSurveyAssembler(register, overdue, open, TODAY, DATES).assemble(ARDBEG).values();
    }

    @Nested
    @DisplayName("filling it from ARDBEG")
    class Ardbeg {

        private final Map<String, String> v = fill(ardbeg(), List.of(), List.of());

        @Test
        void vessel() {
            assertThat(v).containsEntry("Vessels NameRow1", "ARDBEG").containsEntry("IMO NumberRow1", "9301234")
                    .containsEntry("FlagRow1", "Singapore").containsEntry("ClassRow1", "DNV");
        }

        @Test
        @DisplayName("EPIRB No 1 with its battery and HRU; the empty EPIRB No 2 prints blank")
        void epirb() {
            assertThat(v).containsEntry("MakeEPIRB No 1", "JOTRON").containsEntry("ModelEPIRB No 1", "TRON 60AIS")
                    .containsEntry("Date8_af_date.0.0", "30 Apr 2036").containsEntry("Date8_af_date.0.1", "30 May 2027")
                    .containsEntry("MakeEPIRB No 2", "").containsEntry("Date8_af_date.1.0", "");
        }

        @Test
        void sartAndWalkieTalkies() {
            assertThat(v).containsEntry("ModelSART No 1", "TRON SART 20").containsEntry("Date13_af_date.0", "30 May 2030")
                    .containsEntry("MakeGMDSS Walkie Talkie No 3", "JOTRON")
                    .containsEntry("ModelGMDSS Walkie Talkie No 1", "TRON TR 30");
        }

        @Test
        @DisplayName("the VDR at 11 with its four parts at 11.1-11.4")
        void vdr() {
            assertThat(v).containsEntry("MakeVDR", "JRC").containsEntry("ModelVDR", "JCY 1900")
                    .containsEntry("Date11_af_date.0", "28 Feb 2029").containsEntry("Date11_af_date.1", "30 Aug 2027")
                    .containsEntry("Date11_af_date.2", "30 May 2030").containsEntry("Date11_af_date.3", "30 May 2027")
                    .containsEntry("Dropdown4", "Good").containsEntry("Error IF any T", "");
        }

        @Test
        @DisplayName("radars S-band first, as the form lists them, each with its magnetron hours")
        void radars() {
            assertThat(v).containsEntry("Dropdown1.0", "S-Band").containsEntry("ModelRadar No 1", "JMR-9282-S")
                    .containsEntry("Magnetron running Hrs Tx HrsRadar No 1", "6149")
                    .containsEntry("Dropdown1.1.0", "X-Band").containsEntry("ModelRadar No 2", "JM-9225-9X")
                    .containsEntry("Magnetron running Hrs Tx HrsRadar No 2", "323")
                    .containsEntry("Dropdown1.1.1", "").containsEntry("MakeRadar No 3", "");
        }

        @Test
        void ecdisAndGyro() {
            assertThat(v).containsEntry("ModelECDIS No 1", "9201").containsEntry("Date10_af_date.1.0", "14 Mar 2025")
                    .containsEntry("MakeGyro No 1", "TOKYO KEIKI").containsEntry("ModelGyro No 1", "TG 8100");
        }
    }

    @Test
    @DisplayName("a standard-fit vessel numbers the VDR 11.1; it reads the same")
    void standardFit() {
        List<EquipmentRecord> r = List.of(
                item("11.1", "VDR", "Furuno", "VR-7000", null),
                item("11.1.1", "VDR Backup Battery", null, null, LocalDate.of(2028, 1, 31)),
                item("11.1.2", "VDR Acoustic Beacon", null, null, LocalDate.of(2027, 6, 30)),
                item("11.1.3", "VDR FFC Battery", null, null, LocalDate.of(2029, 3, 31)),
                item("11.1.4", "VDR FFC HRU", null, null, LocalDate.of(2027, 9, 30)));
        Map<String, String> v = fill(r, List.of(), List.of());
        assertThat(v).containsEntry("MakeVDR", "Furuno").containsEntry("Date11_af_date.0", "31 Jan 2028")
                .containsEntry("Date11_af_date.1", "30 Jun 2027").containsEntry("Date11_af_date.2", "31 Mar 2029")
                .containsEntry("Date11_af_date.3", "30 Sep 2027");
    }

    @Test
    @DisplayName("an overdue radar or an open request turns its line to Not Good, and says why")
    void statusRule() {
        List<EquipmentRecord> r = ardbeg();
        EquipmentRecord xband = r.stream().filter(e -> e.path().equals("13.1")).findFirst().orElseThrow();
        EquipmentRecord ecdis = r.stream().filter(e -> e.path().equals("14.1")).findFirst().orElseThrow();
        DueItem overdue = new DueItem(xband.spareId(), 5L, "ARDBEG", "X-Band Radar", "13.1", "RADAR",
                TODAY.minusDays(343), -343, DueStatus.OVERDUE, "red", "square", "CALENDAR");
        RequestSummary request = new RequestSummary(9L, "SR-0042", 5L, "ARDBEG", ecdis.spareId(), "ECDIS No 1",
                "14.1", "ECDIS", "Display flickering", null, ServiceRequestStatus.TROUBLESHOOTING, "Troubleshooting",
                1L, "Master", null, null, null, null, 3);

        Map<String, String> v = fill(r, List.of(overdue), List.of(request));
        assertThat(v).containsEntry("Dropdown5", "Not Good")
                .containsEntry("Text12", "X-Band Radar annual overdue 343 d")
                .containsEntry("Dropdown6", "Not Good")
                .containsEntry("Error IF any T_3", "SR-0042 Display flickering")
                .containsEntry("Dropdown4", "Good")
                .containsEntry("Dropdown7", "Good");
    }

    @Test
    @DisplayName("prints on the client's form: values in place, fields gone, page 2 notes intact")
    void renders() throws Exception {
        PreSurveyRenderer renderer = new PreSurveyRenderer();
        assertThat(renderer.slots()).hasSize(71);

        Map<String, String> values = fill(ardbeg(), List.of(), List.of());
        byte[] pdf = renderer.render(values, "Survey at Singapore, 4 Nov 2026. Agent: Wilhelmsen.");
        Path out = Path.of("target", "pre-survey-ardbeg.pdf");
        Files.createDirectories(out.getParent());
        Files.write(out, pdf);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
            assertThat(doc.getDocumentCatalog().getAcroForm()).isNull();
            String text = new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
            assertThat(text).contains("ARDBEG").contains("TRON 60AIS").contains("JCY 1900").contains("6149")
                    .contains("S-Band").contains("Good").contains("30 Apr 2036")
                    .contains("Survey at Singapore, 4 Nov 2026")
                    .contains("IMPORTANT NOTES FOR GMDSS RADIO SURVEY")
                    .contains("QM-F11 / 1 / 0 / May 2018");
        }
    }
}
