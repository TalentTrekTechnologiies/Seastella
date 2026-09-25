package com.seastella.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A Spare's service history, and building a vessel's equipment by hand
 * (SoW §6.3, §9).
 *
 * <p>The history is what a surveyor and an auditor ask for, and a fleet
 * joining the platform arrives with years of it that predates the platform.
 * Two things therefore have to hold: a back-dated entry is accepted and drives
 * the maintenance engine exactly as a platform-recorded one does, and the two
 * kinds stay distinguishable so nobody has to wonder whether the system
 * observed a service or somebody typed it in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-history-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("service history and equipment entry")
class ServiceHistoryIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final String HEAD = "tech.head@acme-shipmanagement.example";
    private static final String CAPTAIN = "master.kestrel@acme-shipmanagement.example";
    private static final String OTHER_HEAD = "tech.head@nordic-tanker.example";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private String head;
    private String captain;
    private String otherHead;
    private long vesselId;

    @BeforeAll
    void setUp() throws Exception {
        head = token(HEAD);
        captain = token(CAPTAIN);
        otherHead = token(OTHER_HEAD);
        vesselId = jdbc.queryForObject(
                "select id from vessel where imo_number = '9412367'", Long.class);
    }

    @Test
    @DisplayName("a service from before the platform is recorded and drives the next due date (SoW §9.3)")
    void backDatedServiceIsRecorded() throws Exception {
        long spareId = addTopLevel("Echo Sounder (history test)");
        LocalDate lastYear = LocalDate.now().minusMonths(14);

        assertThat(body(fetch("/api/v1/spares/" + spareId + "/service-history", head), 200))
                .as("nothing yet").isEmpty();

        JsonNode recorded = body(postJson("/api/v1/spares/" + spareId + "/service-history", head, Map.of(
                "serviceDate", lastYear.toString(),
                "workPerformed", "Annual performance test; transducer cable replaced.",
                "performedBy", "Marine Electronics Pte Ltd",
                "partsUsed", "Transducer cable 12 m")), 201);
        assertThat(recorded.path("source").asText()).isEqualTo("RECORDED");
        assertThat(recorded.path("removable").asBoolean()).as("typed in, so it can be taken out").isTrue();

        // The spare's own date follows the history…
        assertThat(jdbc.queryForObject(
                "select last_annual_service_date from spare where id = ?", LocalDate.class, spareId))
                .isEqualTo(lastYear);
        // …and the maintenance engine has counted from it, so the item is now
        // tracked and overdue rather than untracked.
        JsonNode due = body(fetch("/api/v1/vessels/" + vesselId + "/maintenance", head), 200);
        JsonNode mine = one(due, spareId);
        assertThat(mine.path("status").asText()).isNotEqualTo("NOT_TRACKED");
        assertThat(mine.path("nextDueDate").asText()).isEqualTo(lastYear.plusYears(1).toString());
    }

    @Test
    @DisplayName("an older entry is kept but does not move the last service backwards")
    void olderEntryDoesNotMoveTheDateBack() throws Exception {
        long spareId = addTopLevel("Gyro Compass (history test)");
        LocalDate recent = LocalDate.now().minusMonths(3);
        LocalDate older = LocalDate.now().minusYears(4);

        record(spareId, recent, "Most recent service");
        record(spareId, older, "An older service, entered afterwards");

        assertThat(body(fetch("/api/v1/spares/" + spareId + "/service-history", head), 200))
                .as("both are history").hasSize(2);
        assertThat(jdbc.queryForObject(
                "select last_annual_service_date from spare where id = ?", LocalDate.class, spareId))
                .as("the newest service it has actually had").isEqualTo(recent);
    }

    @Test
    @DisplayName("a future service is refused, and so is one before installation")
    void impossibleDatesAreRefused() throws Exception {
        long spareId = addTopLevel("Speed Log (history test)");
        jdbc.update("update spare set installation_date = ? where id = ?",
                java.sql.Date.valueOf(LocalDate.now().minusYears(2)), spareId);

        assertThat(postJson("/api/v1/spares/" + spareId + "/service-history", head, Map.of(
                "serviceDate", LocalDate.now().plusDays(1).toString(),
                "workPerformed", "Not yet done")).getResponse().getStatus()).isEqualTo(400);

        assertThat(postJson("/api/v1/spares/" + spareId + "/service-history", head, Map.of(
                "serviceDate", LocalDate.now().minusYears(5).toString(),
                "workPerformed", "Before it was even fitted")).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("master data is the Technical Head's; another fleet's is not found")
    void onlyTheRightPeopleMayRecord() throws Exception {
        long spareId = addTopLevel("Radar (permission test)");

        // The Captain reads their own equipment's history but does not write it.
        assertThat(fetch("/api/v1/spares/" + spareId + "/service-history", captain)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(postJson("/api/v1/spares/" + spareId + "/service-history", captain, Map.of(
                "serviceDate", LocalDate.now().minusDays(5).toString(),
                "workPerformed", "Not mine to record")).getResponse().getStatus()).isEqualTo(403);

        // Another organization's Technical Head cannot even see it (S-08).
        assertThat(fetch("/api/v1/spares/" + spareId + "/service-history", otherHead)
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(postJson("/api/v1/vessels/" + vesselId + "/spares", otherHead, Map.of(
                "name", "Not their vessel", "equipmentCategoryId", categoryId()))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("equipment is added with a derived VMP number, and a component nests beneath it (SoW §9)")
    void equipmentAndComponentsAreAdded() throws Exception {
        JsonNode unit = body(postJson("/api/v1/vessels/" + vesselId + "/spares", head, Map.of(
                "name", "Auxiliary Radar",
                "equipmentCategoryId", categoryId(),
                "make", "Furuno",
                "model", "FAR-2228",
                "serialNumber", "SN-AUX-001",
                "criticality", "HIGH")), 201);
        long unitId = unit.path("id").asLong();
        String unitPath = unit.path("path").asText();
        assertThat(unitPath).as("a number was derived, not asked for").isNotBlank();

        // A component inside it takes the next number beneath its parent.
        JsonNode part = body(postJson("/api/v1/vessels/" + vesselId + "/spares", head, Map.of(
                "name", "Scanner Motor",
                "parentSpareId", unitId)), 201);
        assertThat(part.path("path").asText()).startsWith(unitPath + ".");
        assertThat(part.path("parentSpareId").asLong()).isEqualTo(unitId);

        // A second component does not collide with the first.
        JsonNode second = body(postJson("/api/v1/vessels/" + vesselId + "/spares", head, Map.of(
                "name", "Display Fan",
                "parentSpareId", unitId)), 201);
        assertThat(second.path("path").asText()).isNotEqualTo(part.path("path").asText());

        // Both are in the vessel's tree, under the unit.
        JsonNode fit = body(fetch("/api/v1/vessels/" + vesselId + "/spares", head), 200);
        assertThat(fit.path("spares").toString()).contains("Scanner Motor", "Display Fan", "Auxiliary Radar");

        // A name is not optional: an unnamed item is not equipment.
        assertThat(postJson("/api/v1/vessels/" + vesselId + "/spares", head, Map.of(
                "equipmentCategoryId", categoryId())).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("a typed entry can be taken back out; one from a request cannot")
    void onlyHandEnteredRowsCanBeRemoved() throws Exception {
        long spareId = addTopLevel("AIS (removal test)");
        LocalDate when = LocalDate.now().minusMonths(6);
        record(spareId, when, "Entered by mistake");

        JsonNode history = body(fetch("/api/v1/spares/" + spareId + "/service-history", head), 200);
        long recordId = history.get(0).path("id").asLong();

        assertThat(mvc.perform(delete("/api/v1/spares/" + spareId + "/service-history/" + recordId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn()
                .getResponse().getStatus()).isEqualTo(204);

        assertThat(body(fetch("/api/v1/spares/" + spareId + "/service-history", head), 200)).isEmpty();
        // Removing the only service leaves the spare with no service date, and
        // so untracked again — the history is the single source of that date.
        assertThat(jdbc.queryForObject(
                "select last_annual_service_date from spare where id = ?", LocalDate.class, spareId)).isNull();

        // A row written by a completed request belongs to that request's own
        // account of itself and is refused here.
        jdbc.update("""
                insert into spare_service_record
                  (spare_id, vessel_id, service_date, source, work_performed, service_request_id,
                   request_number, created_at, version)
                values (?, ?, ?, 'PLATFORM', 'Completed on the platform', ?, 'SR-TEST-0001',
                        current_timestamp, 0)
                """, spareId, vesselId, java.sql.Date.valueOf(when), 999_001L);
        long platformRow = jdbc.queryForObject(
                "select id from spare_service_record where spare_id = ? order by id desc limit 1",
                Long.class, spareId);

        assertThat(mvc.perform(delete("/api/v1/spares/" + spareId + "/service-history/" + platformRow)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn()
                .getResponse().getStatus()).isEqualTo(409);
    }

    // ---------------------------------------------------------------- helpers

    @Test
    @DisplayName("history downloads as Excel and PDF, and a downloaded file uploads back without duplicates")
    void historyDownloadsAndRoundTrips() throws Exception {
        long spareId = addTopLevel("Speed Log (download test)");
        record(spareId, LocalDate.now().minusMonths(3), "Log calibrated; sensor replaced.");

        // Excel for the one item, in the upload's columns.
        MvcResult xlsx = fetch("/api/v1/service-history/export?spareId=" + spareId, head);
        assertThat(xlsx.getResponse().getStatus()).isEqualTo(200);
        byte[] file = xlsx.getResponse().getContentAsByteArray();
        try (org.apache.poi.ss.usermodel.Workbook book =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(file))) {
            org.apache.poi.ss.usermodel.Sheet sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("IMO Number");
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("Log calibrated; sensor replaced.");
        }

        // Uploading it straight back adds nothing: the entry is already there.
        MvcResult check = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/service-history/import")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "history.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", file))
                        .param("apply", "false")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + head))
                .andReturn();
        JsonNode result = body(check, 200);
        assertThat(result.path("errorCount").asInt()).as(result.toString()).isZero();
        assertThat(result.path("addCount").asInt()).isZero();
        assertThat(result.path("skipCount").asInt()).isEqualTo(1);

        // The same history as a PDF report, for this vessel.
        MvcResult pdf = fetch("/api/v1/reports/service-history/pdf?vesselId=" + vesselId, head);
        assertThat(pdf.getResponse().getStatus()).isEqualTo(200);
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 5)).isEqualTo("%PDF-");

        // Another fleet cannot download it.
        assertThat(fetch("/api/v1/service-history/export?spareId=" + spareId, otherHead).getResponse().getStatus())
                .isEqualTo(404);
    }

    private long addTopLevel(String name) throws Exception {
        return body(postJson("/api/v1/vessels/" + vesselId + "/spares", head, Map.of(
                "name", name, "equipmentCategoryId", categoryId())), 201).path("id").asLong();
    }

    private void record(long spareId, LocalDate date, String work) throws Exception {
        body(postJson("/api/v1/spares/" + spareId + "/service-history", head, Map.of(
                "serviceDate", date.toString(), "workPerformed", work)), 201);
    }

    private long categoryId() {
        return jdbc.queryForObject("select id from equipment_category order by display_order limit 1", Long.class);
    }

    private JsonNode one(JsonNode rows, long spareId) {
        for (JsonNode row : rows) if (row.path("spareId").asLong() == spareId) return row;
        throw new AssertionError("spare " + spareId + " is not in the maintenance list");
    }

    private MvcResult fetch(String path, String token) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private MvcResult postJson(String path, String token, Object payload) throws Exception {
        return mvc.perform(post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload))).andReturn();
    }

    private JsonNode body(MvcResult r, int expected) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String token(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD)))).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("accessToken").asText();
    }
}
