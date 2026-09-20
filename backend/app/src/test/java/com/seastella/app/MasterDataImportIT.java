package com.seastella.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * VMP master-data import (SoW s10): a spreadsheet is staged and previewed, and
 * nothing in the fleet changes until the person who uploaded it confirms what
 * they have read (IMP-07 to IMP-09, security tests S-08 and S-37).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-import-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("VMP master-data import")
class MasterDataImportIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final String TECHNICAL_HEAD = "tech.head@acme-shipmanagement.example";
    private static final List<String> HEADINGS = List.of(
            "IMO Number", "VMP Ref", "Equipment Category", "Spare / Description", "Make", "Model",
            "Serial Number", "Software Version", "Installation Date", "Expiry Date", "Last Annual Service",
            "Last Survey", "Last APT", "Has Hour Meter", "Criticality");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private String head;
    private String admin;
    private long vesselId;
    private String imo;
    private String otherOrgImo;
    private String existingRef;
    private String untouchedRef;

    @BeforeAll
    void setUp() throws Exception {
        head = token(TECHNICAL_HEAD);
        admin = token("admin@seastella.example");

        Map<String, Object> vessel = jdbc.queryForMap("""
                select v.id, v.imo_number from vessel v
                join app_user u on u.organization_id = v.organization_id
                where u.email = ? order by v.id limit 1
                """, TECHNICAL_HEAD);
        vesselId = ((Number) vessel.get("id")).longValue();
        imo = (String) vessel.get("imo_number");
        otherOrgImo = jdbc.queryForObject("""
                select v.imo_number from vessel v
                where v.organization_id <> (select organization_id from app_user where email = ?)
                order by v.id limit 1
                """, String.class, TECHNICAL_HEAD);
        List<String> refs = jdbc.queryForList(
                "select vmp_ref from spare where vessel_id = ? and vmp_ref is not null order by vmp_ref limit 2",
                String.class, vesselId);
        existingRef = refs.get(0);
        untouchedRef = refs.get(1);
    }

    @Test
    @DisplayName("the template carries the VMP columns, and a vessel's own can be downloaded to edit")
    void template() throws Exception {
        MvcResult blank = mvc.perform(get("/api/v1/imports/template").header(HttpHeaders.AUTHORIZATION, "Bearer " + head))
                .andReturn();
        assertThat(blank.getResponse().getStatus()).isEqualTo(200);
        assertThat(blank.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("seastella-spares-template.xlsx");
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(blank.getResponse().getContentAsByteArray()))) {
            Sheet sheet = workbook.getSheet("Spares");
            assertThat(sheet).isNotNull();
            assertThat(headings(sheet)).containsExactlyElementsOf(HEADINGS);
            assertThat(sheet.getLastRowNum()).isZero();
            assertThat(workbook.getSheet("How to use this")).isNotNull();
        }

        MvcResult filled = mvc.perform(get("/api/v1/imports/template?vesselId=" + vesselId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(filled.getResponse().getContentAsByteArray()))) {
            Sheet sheet = workbook.getSheet("Spares");
            assertThat(sheet.getLastRowNum()).isPositive();
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo(imo);
        }

        // Another organization's vessel is not there to download.
        long otherVessel = jdbc.queryForObject("select id from vessel where imo_number = ?", Long.class, otherOrgImo);
        assertThat(mvc.perform(get("/api/v1/imports/template?vesselId=" + otherVessel)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("upload stages every row with what it would do, and changes nothing")
    void previewThenCommit() throws Exception {
        int sparesBefore = spareCount();
        // Top-level references, so the file does not depend on what the standard fit numbers.
        String newRef = "91";
        String childRef = "91.1";
        String current = jdbc.queryForObject("select criticality from spare where vessel_id = ? and vmp_ref = ?",
                String.class, vesselId, existingRef);
        String changedCriticality = "HIGH".equals(current) ? "MEDIUM" : "HIGH";

        byte[] file = workbook(List.of(
                row(imo, existingRef, null, null, "Furuno (corrected)", null, null, null, null, null, "2026-02-01", null, null, null, changedCriticality),
                row(imo, untouchedRef, null, null, null, null, null, null, null, null, null, null, null, null, null),
                row(imo, newRef, "RADAR", "Spare magnetron", "Furuno", "MG-5001", "SN-77", null, "2025-06-01", null, null, null, null, "Yes", "CRITICAL"),
                row(imo, childRef, "RADAR", "Magnetron mount", null, null, null, null, null, null, null, null, null, null, null),
                row(imo, "94.5.1", "RADAR", "Orphan part", null, null, null, null, null, null, null, null, null, null, null),
                row(imo, existingRef, null, null, "A second attempt", null, null, null, null, null, null, null, null, null, null),
                row(otherOrgImo, "13.1", "RADAR", "Another client's radar", null, null, null, null, null, null, null, null, null, null, null),
                row("9999999", "13.1", "RADAR", "Unknown vessel", null, null, null, null, null, null, null, null, null, null, null),
                row(imo, newRef + "2", "RADAR", "Bad date", null, null, null, null, "31/06/2026", null, null, null, null, null, null),
                row(imo, "93", "NOT_A_CATEGORY", "Unknown category", null, null, null, null, null, null, null, null, null, null, null)));

        JsonNode preview = body(upload(head, file), 201);
        Long batchId = preview.path("id").asLong();
        assertThat(preview.path("rowCount").asInt()).isEqualTo(10);
        assertThat(preview.path("newCount").asInt()).isEqualTo(2);
        assertThat(preview.path("modifiedCount").asInt()).isEqualTo(1);
        assertThat(preview.path("unchangedCount").asInt()).isEqualTo(1);
        assertThat(preview.path("duplicateCount").asInt()).isEqualTo(1);
        assertThat(preview.path("invalidCount").asInt()).isEqualTo(5);
        assertThat(preview.path("vessels").asText()).contains(imo);

        Map<String, JsonNode> byRef = rowsByRef(preview);
        assertThat(byRef.get(existingRef).path("outcome").asText()).isEqualTo("MODIFIED");
        assertThat(byRef.get(existingRef).path("changes").findValuesAsText("field")).contains("Make", "Last Annual Service", "Criticality");
        assertThat(byRef.get(untouchedRef).path("outcome").asText()).isEqualTo("UNCHANGED");
        assertThat(byRef.get(newRef).path("outcome").asText()).isEqualTo("NEW");
        assertThat(byRef.get(childRef).path("outcome").asText()).isEqualTo("NEW");
        assertThat(messages(preview, "94.5.1")).contains("Add the parent spare");
        assertThat(messages(preview, "93")).contains("is not an equipment category");
        assertThat(messages(preview, newRef + "2")).contains("is not a date");
        // S-08: another client's vessel, and a vessel that does not exist, read the same.
        assertThat(messages(preview, "13.1")).contains("No vessel with IMO " + otherOrgImo + " in your fleet.");
        assertThat(messages(preview, "13.1")).contains("No vessel with IMO 9999999 in your fleet.");

        // IMP-09: nothing has been written to the fleet yet.
        assertThat(spareCount()).isEqualTo(sparesBefore);

        // S-37: committing something nobody has looked at is refused.
        assertThat(commit(head, batchId).getResponse().getStatus()).isEqualTo(409);

        JsonNode read = body(mvc.perform(get("/api/v1/imports/" + batchId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn(), 200);
        assertThat(read.path("canCommit").asBoolean()).isTrue();

        JsonNode committed = body(commit(head, batchId), 200);
        assertThat(committed.path("status").asText()).isEqualTo("COMMITTED");
        assertThat(committed.path("appliedCount").asInt()).isEqualTo(3);
        assertThat(committed.path("committedBy").asText()).isNotBlank();

        assertThat(spareCount()).isEqualTo(sparesBefore + 2);
        Map<String, Object> changed = jdbc.queryForMap(
                "select make, criticality, last_annual_service_date from spare where vessel_id = ? and vmp_ref = ?",
                vesselId, existingRef);
        assertThat(changed.get("make")).isEqualTo("Furuno (corrected)");
        assertThat(changed.get("criticality")).isEqualTo(changedCriticality);
        assertThat(changed.get("last_annual_service_date").toString()).isEqualTo("2026-02-01");

        // The child was filed under its parent, not left loose.
        Long parentId = jdbc.queryForObject("select id from spare where vessel_id = ? and vmp_ref = ?", Long.class, vesselId, newRef);
        assertThat(jdbc.queryForObject("select parent_spare_id from spare where vessel_id = ? and vmp_ref = ?",
                Long.class, vesselId, childRef)).isEqualTo(parentId);

        // An imported service date starts maintenance tracking, exactly as an edit does.
        assertThat(jdbc.queryForObject("select count(*) from spare_maintenance_rule r "
                        + "join spare s on s.id = r.spare_id where s.vessel_id = ? and s.vmp_ref = ?",
                Integer.class, vesselId, existingRef)).isPositive();

        assertThat(commit(head, batchId).getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from audit_entry where action = 'IMPORT_COMMITTED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a file can be discarded, and then nothing of it is applied")
    void discard() throws Exception {
        int before = spareCount();
        byte[] file = workbook(List.of(
                row(imo, "13.95", "RADAR", "Discarded spare", null, null, null, null, null, null, null, null, null, null, null)));
        Long batchId = body(upload(head, file), 201).path("id").asLong();

        JsonNode discarded = body(mvc.perform(post("/api/v1/imports/" + batchId + "/discard")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn(), 200);
        assertThat(discarded.path("status").asText()).isEqualTo("DISCARDED");
        assertThat(spareCount()).isEqualTo(before);
        assertThat(commit(head, batchId).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("a file that is not the template is refused with an explanation")
    void notTheTemplate() throws Exception {
        MvcResult notXlsx = upload(head, "IMO,VMP\n123,13.1".getBytes());
        assertThat(notXlsx.getResponse().getStatus()).isEqualTo(400);

        byte[] wrongColumns;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Spares");
            Row headings = sheet.createRow(0);
            headings.createCell(0).setCellValue("Vessel");
            headings.createCell(1).setCellValue("Item");
            sheet.createRow(1).createCell(0).setCellValue("MV Something");
            workbook.write(out);
            wrongColumns = out.toByteArray();
        }
        MvcResult refused = mvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile("file", "fleet.xlsx", null, wrongColumns))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + head))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("IMO Number");
    }

    @Test
    @DisplayName("only a Platform Admin or Technical Head imports, and only their own uploads are visible")
    void scope() throws Exception {
        assertThat(upload(token("master.kestrel@acme-shipmanagement.example"), workbook(List.of(
                row(imo, "13.96", "RADAR", "Captain's attempt", null, null, null, null, null, null, null, null, null, null, null))))
                .getResponse().getStatus()).isEqualTo(403);

        byte[] file = workbook(List.of(
                row(otherOrgImo, "13.97", "RADAR", "Platform Admin row", null, null, null, null, null, null, null, null, null, null, null)));
        Long adminBatch = body(upload(admin, file), 201).path("id").asLong();

        // The Platform Admin's upload, on another client's vessel, is not the Technical Head's business.
        assertThat(mvc.perform(get("/api/v1/imports/" + adminBatch).header(HttpHeaders.AUTHORIZATION, "Bearer " + head))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(body(mvc.perform(get("/api/v1/imports").header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn(), 200)
                .findValuesAsText("id")).doesNotContain(String.valueOf(adminBatch));
    }

    // ---------------------------------------------------------------- helpers

    private MvcResult upload(String token, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile("file", "spares.xlsx", null, content))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
    }

    private MvcResult commit(String token, Long batchId) throws Exception {
        return mvc.perform(post("/api/v1/imports/" + batchId + "/commit")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private int spareCount() {
        return jdbc.queryForObject("select count(*) from spare where vessel_id = ?", Integer.class, vesselId);
    }

    private static List<String> row(String... cells) {
        return java.util.Arrays.stream(cells).map(c -> c == null ? "" : c).toList();
    }

    private static byte[] workbook(List<List<String>> rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Spares");
            Row headings = sheet.createRow(0);
            for (int i = 0; i < HEADINGS.size(); i++) {
                headings.createCell(i).setCellValue(HEADINGS.get(i));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> values = rows.get(r);
                for (int i = 0; i < values.size(); i++) {
                    if (!values.get(i).isEmpty()) row.createCell(i).setCellValue(values.get(i));
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static List<String> headings(Sheet sheet) {
        Row row = sheet.getRow(0);
        return java.util.stream.IntStream.range(0, row.getLastCellNum())
                .mapToObj(i -> row.getCell(i).getStringCellValue().replace(" *", ""))
                .toList();
    }

    private Map<String, JsonNode> rowsByRef(JsonNode batch) {
        Map<String, JsonNode> byRef = new java.util.LinkedHashMap<>();
        batch.path("rows").forEach(r -> byRef.putIfAbsent(r.path("vmpRef").asText(), r));
        return byRef;
    }

    private String messages(JsonNode batch, String vmpRef) {
        StringBuilder all = new StringBuilder();
        batch.path("rows").forEach(r -> {
            if (vmpRef.equals(r.path("vmpRef").asText())) all.append(r.path("messages").asText()).append(' ');
        });
        return all.toString();
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
