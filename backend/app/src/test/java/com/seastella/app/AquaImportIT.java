package com.seastella.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * A client's own equipment list goes in whole: every row of AQUA 1's real
 * sheet lands on the vessel, including equipment the sheet names in its own
 * words ("GMDSS WALKY TALKY") and kinds the platform does not list yet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-aqua-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("importing AQUA 1's own equipment list")
class AquaImportIT {

    private static final Path AQUA_FILE =
            Path.of("..", "masterdata-import", "src", "test", "resources", "client-equipment-list.xlsx");
    private static final String AQUA_IMO = "9573660";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private String head;
    private long vesselId;

    @BeforeAll
    void setUp() throws Exception {
        head = token("tech.head@acme-shipmanagement.example");
        long orgId = jdbc.queryForObject(
                "select organization_id from app_user where email = 'tech.head@acme-shipmanagement.example'", Long.class);
        Map<String, Object> vessel = new HashMap<>();
        vessel.put("organizationId", orgId);
        vessel.put("name", "AQUA 1");
        vessel.put("imoNumber", AQUA_IMO);
        vessel.put("standardFit", false);
        JsonNode created = body(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/vessels").header(HttpHeaders.AUTHORIZATION, "Bearer " + head)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(vessel))).andReturn(), 201);
        vesselId = created.path("id").asLong();
    }

    @Test
    @Order(1)
    @DisplayName("every one of the sheet's 63 equipment rows is imported, the walkie-talkies included")
    void wholeSheetGoesIn() throws Exception {
        JsonNode batch = body(upload(Files.readAllBytes(AQUA_FILE)), 201);
        List<JsonNode> equipment = rows(batch, "EQUIPMENT");
        List<String> refused = new ArrayList<>();
        equipment.forEach(r -> {
            if (!"NEW".equals(r.path("outcome").asText())) {
                refused.add(r.path("vmpRef").asText() + " " + r.path("outcome").asText() + ": " + r.path("messages").asText());
            }
        });
        assertThat(refused).as("rows not going in").isEmpty();
        assertThat(equipment).hasSize(63);

        long batchId = batch.path("id").asLong();
        body(get("/api/v1/imports/" + batchId), 200);          // the preview is read before committing
        body(post("/api/v1/imports/" + batchId + "/commit"), 200);

        assertThat(jdbc.queryForObject("select count(*) from spare where vessel_id = ?", Integer.class, vesselId))
                .isEqualTo(63);
        for (String ref : List.of("10", "10.1", "10.2", "10.3")) {
            assertThat(categoryOf(ref)).as("walkie-talkie " + ref).isEqualTo("GMDSS_WT");
        }
        assertThat(jdbc.queryForObject("select serial_number from spare where vessel_id = ? and vmp_ref = '10.2'",
                String.class, vesselId)).isEqualTo("JHS40131");
        // What the file does not say is left empty, never filled in.
        assertThat(jdbc.queryForObject("select make from spare where vessel_id = ? and vmp_ref = '13.1.2'",
                String.class, vesselId)).isNull();
    }

    @Test
    @Order(2)
    @DisplayName("a kind of equipment the platform does not list is added under the sheet's own name")
    void unknownKindIsAdded() throws Exception {
        byte[] file = workbook(sheet -> {
            row(sheet, 0, "VESSEL NAME", "IMO NUMBER", "ID", "Description", "Make", "Expiration Dates (DD-MM-YYYY)");
            row(sheet, 1, "AQUA 1", AQUA_IMO, "30", "MAGNETIC COMPASS", "CASSENS & PLATH", null);
            row(sheet, 2, null, null, "30.1", "COMPASS LIGHT", "CASSENS & PLATH", "SPARE BATT EXP : 30-05-2030");
        });
        JsonNode batch = body(upload(file), 201);
        List<JsonNode> equipment = rows(batch, "EQUIPMENT");
        assertThat(equipment).extracting(r -> r.path("outcome").asText()).containsOnly("NEW");
        assertThat(batch.toString()).contains("MAGNETIC COMPASS (new type)");
        assertThat(jdbc.queryForObject("select count(*) from equipment_category where name = 'MAGNETIC COMPASS'",
                Integer.class)).as("nothing is created before the commit").isZero();

        long batchId = batch.path("id").asLong();
        body(get("/api/v1/imports/" + batchId), 200);
        body(post("/api/v1/imports/" + batchId + "/commit"), 200);

        assertThat(jdbc.queryForObject("select count(*) from equipment_category where name = 'MAGNETIC COMPASS'",
                Integer.class)).as("added once, for the heading and its item").isEqualTo(1);
        assertThat(categoryName("30")).isEqualTo("MAGNETIC COMPASS");
        assertThat(categoryName("30.1")).isEqualTo("MAGNETIC COMPASS");
        // A date written inside a note is still read.
        assertThat(jdbc.queryForObject("select expiration_date from spare where vessel_id = ? and vmp_ref = '30.1'",
                java.sql.Date.class, vesselId).toString()).isEqualTo("2030-05-30");
    }

    private String categoryOf(String ref) {
        return jdbc.queryForObject("""
                select c.code from spare s join equipment_category c on c.id = s.equipment_category_id
                where s.vessel_id = ? and s.vmp_ref = ?""", String.class, vesselId, ref);
    }

    private String categoryName(String ref) {
        return jdbc.queryForObject("""
                select c.name from spare s join equipment_category c on c.id = s.equipment_category_id
                where s.vessel_id = ? and s.vmp_ref = ?""", String.class, vesselId, ref);
    }

    private List<JsonNode> rows(JsonNode batch, String kind) {
        List<JsonNode> found = new ArrayList<>();
        batch.path("rows").forEach(r -> {
            if (kind.equals(r.path("kind").asText())) found.add(r);
        });
        return found;
    }

    private MvcResult upload(byte[] content) throws Exception {
        return mvc.perform(multipart("/api/v1/imports")
                .file(new MockMultipartFile("file", "aqua-1.xlsx", null, content))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn();
    }

    private MvcResult get(String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn();
    }

    private MvcResult post(String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn();
    }

    private static byte[] workbook(java.util.function.Consumer<Sheet> fill) throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fill.accept(book.createSheet("Sheet1"));
            book.write(out);
            return out.toByteArray();
        }
    }

    private static void row(Sheet sheet, int index, String... values) {
        Row row = sheet.createRow(index);
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) row.createCell(i).setCellValue(values[i]);
        }
    }

    private JsonNode body(MvcResult r, int expectedStatus) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String token(String email) throws Exception {
        MvcResult r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", "SeaStella#Demo2026")))).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("accessToken").asText();
    }
}
