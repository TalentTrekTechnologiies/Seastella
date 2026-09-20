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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The service request from the bridge to the invoice and back (SoW §6, §8):
 * raised by the Captain, approved by the Ship Manager, invoiced by the
 * Coordinator, accepted before any engineer is assigned, completed by the
 * engineer, and closed - with the spare's own service record updated (SRQ-19).
 *
 * <p>Two things are checked that no single screen shows: the invoice gate
 * (G3 - no engineer before acceptance) and that a Captain never receives an
 * invoice amount (SoW §12).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "seastella.upload.storage-dir=./target/test-request-files",
        "spring.datasource.url=jdbc:h2:mem:seastella-flow-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("service request end to end")
class ServiceRequestFlowIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final String CAPTAIN = "master.kestrel@acme-shipmanagement.example";
    private static final String SHIP_MANAGER = "d.fernandes@acme-shipmanagement.example";
    private static final String COORDINATOR = "coordinator@seastella.example";
    private static final String ENGINEER = "t.okafor@marine-electronics.example";

    /** A one-pixel PNG: what matters is that it starts with the PNG signature. */
    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    /** MZ: a Windows executable, whatever it is called. */
    private static final byte[] EXECUTABLE = new byte[]{0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00};

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private String captain;
    private String shipManager;
    private String coordinator;
    private String engineer;
    private long spareId;

    @BeforeAll
    void setUp() throws Exception {
        captain = token(CAPTAIN);
        shipManager = token(SHIP_MANAGER);
        coordinator = token(COORDINATOR);
        engineer = token(ENGINEER);
        // A spare on the Captain's vessel that no seeded request is already using.
        spareId = jdbc.queryForObject("""
                select s.id from spare s
                join user_vessel_assignment a on a.vessel_id = s.vessel_id
                join app_user u on u.id = a.user_id
                where u.email = ? and s.id not in (select spare_id from service_request)
                order by s.id limit 1
                """, Long.class, CAPTAIN);
    }

    @Test
    @DisplayName("bridge to invoice to engineer to closed, with the spare's service record updated")
    void fullFlow() throws Exception {
        LocalDate serviceDate = LocalDate.now().minusDays(1);
        LocalDate before = jdbc.queryForObject(
                "select last_annual_service_date from spare where id = ?", LocalDate.class, spareId);

        // 1. The Captain raises it.
        JsonNode raised = body(postJson("/api/v1/service-requests", captain, Map.of(
                "spareId", spareId, "title", "Display flickering on the radar",
                "description", "Picture drops out for a second every few minutes.", "priority", "HIGH")), 201);
        long requestId = raised.path("request").path("id").asLong();
        String number = raised.path("request").path("requestNumber").asText();
        assertThat(raised.path("request").path("status").asText()).isEqualTo("REPORTED");

        // SoW §6.1: the request carries supporting photos/video (SRQ-02). The
        // Coordinator and the engineer both see what the Captain photographed.
        JsonNode photo = body(attach(captain, requestId, PNG, "scanner.png", "Burn mark on the display board"), 201);
        assertThat(photo.path("image").asBoolean()).isTrue();
        assertThat(body(getJson("/api/v1/service-requests/" + requestId + "/attachments", coordinator), 200))
                .hasSize(1);
        // A file is judged by its bytes, not its name (SEC-15).
        assertThat(attach(captain, requestId, EXECUTABLE, "photo.png", null).getResponse().getStatus())
                .isEqualTo(400);

        // 2. The guided checks come first: a request cannot go for approval
        //    until the Captain has worked through them (SoW §6.1).
        MvcResult tooEarly = mvc.perform(post("/api/v1/service-requests/" + requestId + "/actions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + captain)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("action", "SUBMIT_FOR_APPROVAL"))))
                .andReturn();
        assertThat(tooEarly.getResponse().getStatus()).isEqualTo(409);
        runGuidedChecks(requestId);
        assertThat(status(requestId)).isEqualTo("TROUBLESHOOTING");

        // 3. Submitted for approval, approved by the Ship Manager.
        act(captain, requestId, "SUBMIT_FOR_APPROVAL", null, 200);
        assertThat(status(requestId)).isEqualTo("PENDING_OPERATIONAL_APPROVAL");
        act(shipManager, requestId, "APPROVE_OPERATIONAL", null, 200);
        assertThat(status(requestId)).isEqualTo("OPERATIONALLY_APPROVED");

        // 4. G3: no engineer may be assigned before an invoice is accepted.
        long engineerId = jdbc.queryForObject("select id from app_user where email = ?", Long.class, ENGINEER);
        MvcResult beforeInvoice = mvc.perform(post("/api/v1/service-requests/" + requestId + "/actions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + coordinator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("action", "ASSIGN_ENGINEER", "engineerUserId", engineerId))))
                .andReturn();
        assertThat(beforeInvoice.getResponse().getStatus()).isEqualTo(409);

        // 5. The Coordinator raises an invoice; the Ship Manager accepts it.
        JsonNode invoice = body(postJson("/api/v1/service-requests/" + requestId + "/invoices", coordinator, Map.of(
                "amount", "1850.00", "currency", "USD", "description", "Radar display replacement")), 201);
        long invoiceId = invoice.path("invoiceId").asLong();
        assertThat(status(requestId)).isEqualTo("INVOICE_RAISED");

        // SoW §12: the Captain may follow their request but never sees the amount.
        JsonNode captainView = body(getJson("/api/v1/service-requests/" + requestId, captain), 200);
        assertThat(captainView.path("financialsVisible").asBoolean()).isFalse();
        assertThat(captainView.toString()).doesNotContain("1850");

        body(postJson("/api/v1/invoices/" + invoiceId + "/decision", shipManager,
                Map.of("decision", "ACCEPT", "note", "Agreed, proceed.")), 200);
        assertThat(status(requestId)).isEqualTo("INVOICE_ACCEPTED");

        // 6. Now the engineer can be assigned, and does the work.
        act(coordinator, requestId, "ASSIGN_ENGINEER", engineerId, 200);
        assertThat(status(requestId)).isEqualTo("ENGINEER_ASSIGNED");
        act(engineer, requestId, "START_WORK", null, 200);

        body(postJson("/api/v1/service-requests/" + requestId + "/completion-report", engineer, Map.of(
                "workPerformed", "Replaced the display unit and ran a full performance test.",
                "partsUsed", "Display unit FR-2117",
                "outcome", "Picture stable through a two-hour test.",
                "serviceDate", serviceDate.toString())), 200);
        assertThat(status(requestId)).isEqualTo("COMPLETION_REPORTED");

        // 7. The Coordinator closes it.
        act(coordinator, requestId, "COMPLETE", null, 200);
        assertThat(status(requestId)).isEqualTo("COMPLETED");

        // SRQ-19: the spare now carries the date it was serviced…
        LocalDate after = jdbc.queryForObject(
                "select last_annual_service_date from spare where id = ?", LocalDate.class, spareId);
        assertThat(after).isEqualTo(serviceDate).isNotEqualTo(before);
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_entry
                where action = 'SERVICE_DATE_CHANGED' and entity_id = ? and after_value like ?
                """, Integer.class, spareId, "%" + number + "%")).isEqualTo(1);

        // …and the maintenance cycle was restarted from it (SoW §18).
        LocalDate nextDue = jdbc.queryForObject("""
                select max(next_due_date) from spare_maintenance_rule where spare_id = ? and active = true
                """, LocalDate.class, spareId);
        assertThat(nextDue).isNotNull().isAfter(LocalDate.now());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Starts the checks and answers "no" through to an outcome, then records
     * what was found - the Captain's path before a request may be submitted.
     */
    private void runGuidedChecks(long requestId) throws Exception {
        JsonNode view = body(postJson("/api/v1/service-requests/" + requestId + "/troubleshooting", captain,
                Map.of()), 200);

        for (int answered = 0; answered < 20; answered++) {
            JsonNode step = view.path("session").path("currentStep");
            if (step.isMissingNode() || step.isNull()) break;
            view = body(postJson("/api/v1/service-requests/" + requestId + "/troubleshooting/answers", captain,
                    Map.of("stepId", step.path("id").asLong(), "yes", false)), 200);
        }
        assertThat(view.path("session").path("status").asText()).isEqualTo("OUTCOME_REACHED");

        body(postJson("/api/v1/service-requests/" + requestId + "/troubleshooting/completion", captain, Map.of(
                "rootCauseNote", "Display unit itself; power and connections are sound.",
                "temporaryFixNote", "Running on the second display for now.")), 200);
    }

    private MvcResult attach(String token, long requestId, byte[] content, String fileName, String caption)
            throws Exception {
        org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder request =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/service-requests/" + requestId + "/attachments")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", fileName, null, content));
        if (caption != null) request.param("caption", caption);
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private String status(long requestId) throws Exception {
        return jdbc.queryForObject("select status from service_request where id = ?", String.class, requestId);
    }

    private void act(String token, long requestId, String action, Long engineerUserId, int expected) throws Exception {
        Map<String, Object> payload = engineerUserId == null
                ? Map.of("action", action)
                : Map.of("action", action, "engineerUserId", engineerUserId);
        MvcResult result = mvc.perform(post("/api/v1/service-requests/" + requestId + "/actions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(action + ": " + result.getResponse().getContentAsString())
                .isEqualTo(expected);
    }

    private MvcResult postJson(String path, String token, Object payload) throws Exception {
        return mvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
    }

    private MvcResult getJson(String path, String token) throws Exception {
        return mvc.perform(get(path)
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
