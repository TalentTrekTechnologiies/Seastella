package com.seastella.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Importing a client's own workbook, rather than our template.
 *
 * <p>A fleet moving onto the platform sends the file it already keeps: the
 * vessel's particulars at the top, its equipment beneath them, and a
 * minimum-spares form on another tab. One upload has to land all three in their
 * own places - vessel, equipment tree, critical spares - and still refuse to
 * change anything until the person who uploaded it has read the preview
 * (IMP-08, IMP-09).
 *
 * <p>{@link MasterDataImportIT} covers the same guarantees for our own
 * template. This is the other kind of file.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-client-import-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("importing a client's own workbook")
class ClientSheetImportIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final String TECHNICAL_HEAD = "tech.head@acme-shipmanagement.example";

    /** Far above anything the seed uses, so the tree it builds stands on its own. */
    private static final String RADAR = "71";
    private static final String X_BAND = "71.1";
    private static final String MAGNETRON = "71.1.1";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private String head;
    private long vesselId;
    private String imo;

    @BeforeAll
    void setUp() throws Exception {
        head = token(TECHNICAL_HEAD);
        Map<String, Object> vessel = jdbc.queryForMap("""
                select v.id, v.imo_number from vessel v
                join app_user u on u.organization_id = v.organization_id
                where u.email = ? order by v.id limit 1
                """, TECHNICAL_HEAD);
        vesselId = ((Number) vessel.get("id")).longValue();
        imo = (String) vessel.get("imo_number");
    }

    @Test
    @Order(1)
    @DisplayName("the preview sorts the workbook into vessel, equipment and critical spares")
    void previewSeparatesTheThreeKinds() throws Exception {
        JsonNode batch = body(upload(head, clientWorkbook()), 201);

        assertThat(kinds(batch, "VESSEL")).hasSize(1);
        assertThat(kinds(batch, "EQUIPMENT")).hasSize(3);
        assertThat(kinds(batch, "CRITICAL_SPARE")).hasSize(2);

        // Nothing has happened yet: an upload only stages (IMP-09).
        assertThat(partNamed("Magnetron")).isNull();
        assertThat(spareAt(X_BAND)).isNull();
    }

    @Test
    @Order(2)
    @DisplayName("the vessel's own particulars are read from the top of the sheet")
    void vesselParticularsAreStaged() throws Exception {
        JsonNode batch = body(upload(head, clientWorkbook()), 201);
        JsonNode vessel = kinds(batch, "VESSEL").get(0);

        // The vessel exists, so the sheet changes it; it is never "new".
        assertThat(vessel.path("outcome").asText()).isEqualTo("MODIFIED");
        assertThat(changeAfter(vessel, "MMSI")).isEqualTo("352004089");
        assertThat(changeAfter(vessel, "CALL_SIGN")).isEqualTo("3E7602");
        assertThat(changeAfter(vessel, "FLAG")).isEqualTo("PANAMA");
    }

    @Test
    @Order(3)
    @DisplayName("a sheet naming a vessel we do not hold is refused, not invented")
    void unknownVesselIsRefused() throws Exception {
        byte[] file = workbook(sheet -> {
            heading(sheet, "IMO Number", "Vessel Name", "ID", "Description");
            write(sheet, 1, "9999999", "NOT OUR SHIP", "80", "RADAR");
        });
        JsonNode batch = body(upload(head, file), 201);
        JsonNode vessel = kinds(batch, "VESSEL").get(0);

        assertThat(vessel.path("outcome").asText()).isEqualTo("INVALID");
        assertThat(vessel.path("messages").asText()).contains("No vessel with IMO 9999999");
        assertThat(jdbc.queryForObject("select count(*) from vessel where imo_number = ?",
                Integer.class, "9999999")).isZero();
    }

    @Test
    @Order(4)
    @DisplayName("committing lands the vessel, its equipment tree and its critical spares")
    void commitAppliesEveryKind() throws Exception {
        Long batchId = body(upload(head, clientWorkbook()), 201).path("id").asLong();
        // G8 / S-37: reading the preview is what earns the right to commit.
        body(detail(head, batchId), 200);
        body(commit(head, batchId), 200);

        // --- the vessel itself
        Map<String, Object> vessel = jdbc.queryForMap(
                "select name, mmsi, call_sign, flag, vessel_class from vessel where id = ?", vesselId);
        assertThat(vessel.get("mmsi")).isEqualTo("352004089");
        assertThat(vessel.get("call_sign")).isEqualTo("3E7602");
        assertThat(vessel.get("flag")).isEqualTo("PANAMA");
        assertThat(vessel.get("vessel_class")).isEqualTo("ABS");

        // --- the equipment, as a tree
        Long radar = spareAt(RADAR);
        Long xBand = spareAt(X_BAND);
        Long magnetron = spareAt(MAGNETRON);
        assertThat(radar).isNotNull();
        assertThat(xBand).isNotNull();
        assertThat(magnetron).isNotNull();
        assertThat(parentOf(xBand)).isEqualTo(radar);
        assertThat(parentOf(magnetron)).isEqualTo(xBand);
        assertThat(jdbc.queryForObject("select make from spare where id = ?", String.class, xBand))
                .isEqualTo("JRC");

        // --- the critical spares, hung on the equipment the form named
        assertThat(partNamed("Magnetron")).isNotNull();
        assertThat(partNamed("Carbon brush")).isNotNull();
    }

    @Test
    @Order(5)
    @DisplayName("a minimum nobody can count is kept as words, and N/A is not a blank")
    void theSparesFormIsReadAsWritten() {
        Map<String, Object> magnetron = jdbc.queryForMap("""
                select minimum_quantity, minimum_note, compliance, remarks, critical, spare_id
                from replacement_part where vessel_id = ? and name = ?
                """, vesselId, "Magnetron");
        assertThat(magnetron.get("minimum_quantity")).isEqualTo(1);
        assertThat(magnetron.get("compliance")).isEqualTo("NO");
        assertThat(magnetron.get("remarks")).isEqualTo("Requisition 4471 raised");
        assertThat(magnetron.get("critical")).isEqualTo(Boolean.TRUE);
        assertThat(((Number) magnetron.get("spare_id")).longValue())
                .as("the form names X-Band RADAR No.3, which this same file created")
                .isEqualTo(spareAt(X_BAND));

        Map<String, Object> brush = jdbc.queryForMap("""
                select minimum_quantity, minimum_note, compliance, spare_id
                from replacement_part where vessel_id = ? and name = ?
                """, vesselId, "Carbon brush");
        assertThat(brush.get("minimum_quantity")).as("\"2 pcs\" counts as 2 for the shortage alert").isEqualTo(2);
        assertThat(brush.get("minimum_note")).isEqualTo("2 pcs");
        assertThat(brush.get("compliance")).as("N/A is an answer, not an empty cell").isEqualTo("NA");
        assertThat(((Number) brush.get("spare_id")).longValue())
                .as("a blank equipment cell means the equipment on the line above")
                .isEqualTo(spareAt(X_BAND));
    }

    @Test
    @Order(6)
    @DisplayName("importing the same file twice updates what it made, and does not duplicate it")
    void reimportIsIdempotent() throws Exception {
        int partsBefore = countParts();
        int sparesBefore = countSpares();

        Long batchId = body(upload(head, clientWorkbook()), 201).path("id").asLong();
        JsonNode preview = body(detail(head, batchId), 200);

        // Everything already matches, so there is nothing left to apply.
        assertThat(preview.path("newCount").asInt()).isZero();
        assertThat(commit(head, batchId).getResponse().getStatus())
                .as("a commit with nothing to do is refused rather than pretending")
                .isEqualTo(409);

        assertThat(countParts()).isEqualTo(partsBefore);
        assertThat(countSpares()).isEqualTo(sparesBefore);
    }

    // ---------------------------------------------------------------- the file

    /**
     * A client's workbook: particulars and equipment on one tab, the
     * minimum-spares form on another, and no column named the way ours are.
     */
    private byte[] clientWorkbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet equipment = workbook.createSheet("Equipment List");
            heading(equipment, "IMO Number", "Vessel Name", "MMSI", "Call Sign", "Flag", "Class",
                    "ID", "Description", "Make", "Model", "Serial No", "Date of Installation");
            // The client fills the vessel columns on the first row only.
            write(equipment, 1, imo, "AQUA 1", "352004089", "3E7602", "PANAMA", "ABS",
                    RADAR, "RADAR", "", "", "", "");
            write(equipment, 2, "", "", "", "", "", "",
                    X_BAND, "X-Band RADAR No.3", "JRC", "JMA-9122-6XA", "LB36406", "2018-05-01");
            // No category in its name: it takes the radar's, which is what 71.1.1 means.
            write(equipment, 3, "", "", "", "", "", "",
                    MAGNETRON, "MAGNETRON", "", "", "", "");

            Sheet spares = workbook.createSheet("Minimum Spares");
            heading(spares, "S.No.", "Equipment", "Spare Part Name", "Minimum Quantity",
                    "Quantity On Hand", "Compliance", "Remarks");
            write(spares, 1, "1", "X-Band RADAR No.3", "Magnetron", "1", "0", "No", "Requisition 4471 raised");
            // The form names the equipment once and leaves it blank beneath.
            write(spares, 2, "", "", "Carbon brush", "2 pcs", "2", "NA", "");

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private byte[] workbook(java.util.function.Consumer<Sheet> build) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            build.accept(workbook.createSheet("Sheet1"));
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static void heading(Sheet sheet, String... headings) {
        write(sheet, 0, headings);
    }

    private static void write(Sheet sheet, int rowNumber, String... values) {
        Row row = sheet.createRow(rowNumber);
        for (int i = 0; i < values.length; i++) {
            if (!values[i].isEmpty()) row.createCell(i).setCellValue(values[i]);
        }
    }

    // ---------------------------------------------------------------- helpers

    private List<JsonNode> kinds(JsonNode batch, String kind) {
        List<JsonNode> found = new java.util.ArrayList<>();
        batch.path("rows").forEach(r -> {
            if (kind.equals(r.path("kind").asText())) found.add(r);
        });
        return found;
    }

    private String changeAfter(JsonNode row, String field) {
        for (JsonNode change : row.path("changes")) {
            if (field.equalsIgnoreCase(change.path("field").asText().replace(" ", "_"))) {
                return change.path("after").asText();
            }
        }
        // The preview labels changes for people ("Call Sign"), so match on that too.
        for (JsonNode change : row.path("changes")) {
            if (change.path("after").asText().length() > 0
                    && field.replace("_", "").equalsIgnoreCase(change.path("field").asText().replace(" ", ""))) {
                return change.path("after").asText();
            }
        }
        return null;
    }

    private Long spareAt(String vmpRef) {
        List<Long> found = jdbc.queryForList(
                "select id from spare where vessel_id = ? and vmp_ref = ?", Long.class, vesselId, vmpRef);
        return found.isEmpty() ? null : found.get(0);
    }

    private Long parentOf(Long spareId) {
        return jdbc.queryForObject("select parent_spare_id from spare where id = ?", Long.class, spareId);
    }

    private Long partNamed(String name) {
        List<Long> found = jdbc.queryForList(
                "select id from replacement_part where vessel_id = ? and name = ?", Long.class, vesselId, name);
        return found.isEmpty() ? null : found.get(0);
    }

    private int countParts() {
        return jdbc.queryForObject("select count(*) from replacement_part where vessel_id = ?", Integer.class, vesselId);
    }

    private int countSpares() {
        return jdbc.queryForObject("select count(*) from spare where vessel_id = ?", Integer.class, vesselId);
    }

    private MvcResult upload(String token, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile("file", "fleet-equipment.xlsx", null, content))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
    }

    private MvcResult detail(String token, Long batchId) throws Exception {
        return mvc.perform(get("/api/v1/imports/" + batchId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private MvcResult commit(String token, Long batchId) throws Exception {
        return mvc.perform(post("/api/v1/imports/" + batchId + "/commit")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private JsonNode body(MvcResult r, int expectedStatus) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String token(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD)))).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("accessToken").asText();
    }
}
