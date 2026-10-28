package com.seastella.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The developer brief of 27 Oct 2026, end to end: the SBMC is the Platform
 * Administrator's alone; the pre-survey form is theirs and a Service
 * Coordinator's for the organizations they serve; the Platform Administrator
 * can open every vessel. Every refusal is the server's, not the screen's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "seastella.upload.storage-dir=./target/test-vessel-documents",
        "spring.datasource.url=jdbc:h2:mem:seastella-vessel-docs-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("vessel details, SBMC and pre-survey form")
class VesselDocumentsIT {

    private static final String PASSWORD = "SeaStella#Demo2026";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private final Map<String, String> tokens = new HashMap<>();
    private long kestrel;   // Acme
    private long bergen;    // Nordic

    @BeforeAll
    void setUp() {
        kestrel = jdbc.queryForObject("select id from vessel where name = 'MV Kestrel Trader'", Long.class);
        bergen = jdbc.queryForObject("select v.id from vessel v join organization o on o.id = v.organization_id "
                + "where o.code <> 'ACME' order by v.id limit 1", Long.class);
    }

    @Nested
    @DisplayName("who may do what")
    class Permissions {

        @Test
        @DisplayName("only the Platform Administrator may preview, issue or revoke an SBMC")
        void sbmcIsTheAdminsAlone() throws Exception {
            for (String who : new String[]{"coordinator@seastella.example", "tech.head@acme-shipmanagement.example",
                    "d.fernandes@acme-shipmanagement.example", "master.kestrel@acme-shipmanagement.example"}) {
                assertThat(status(call(get("/api/v1/vessels/" + kestrel + "/sbmc/preview"), who, null)))
                        .as(who).isEqualTo(403);
                assertThat(status(call(post("/api/v1/vessels/" + kestrel + "/sbmc"), who,
                        Map.of("issueDate", LocalDate.now().toString())))).as(who).isEqualTo(403);
            }
        }

        @Test
        @DisplayName("a coordinator prepares a pre-survey form only for an organization they serve")
        void preSurveyScope() throws Exception {
            assertThat(status(call(get("/api/v1/vessels/" + kestrel + "/pre-survey/draft"),
                    "coordinator@seastella.example", null))).isEqualTo(200);
            assertThat(status(call(get("/api/v1/vessels/" + kestrel + "/pre-survey/draft"),
                    "coordinator.nordic@seastella.example", null))).isEqualTo(403);
            assertThat(status(call(post("/api/v1/vessels/" + kestrel + "/pre-survey"),
                    "coordinator.nordic@seastella.example", Map.of()))).isEqualTo(403);
            assertThat(status(call(get("/api/v1/vessels/" + bergen + "/pre-survey/draft"),
                    "coordinator.nordic@seastella.example", null))).isEqualTo(200);
            for (String who : new String[]{"tech.head@acme-shipmanagement.example",
                    "d.fernandes@acme-shipmanagement.example", "master.kestrel@acme-shipmanagement.example"}) {
                assertThat(status(call(get("/api/v1/vessels/" + kestrel + "/pre-survey/draft"), who, null)))
                        .as(who).isEqualTo(403);
            }
        }

        @Test
        @DisplayName("the Platform Administrator opens any vessel; a coordinator lists only what they serve")
        void vesselAccess() throws Exception {
            JsonNode details = body(call(get("/api/v1/vessels/" + kestrel), "admin@seastella.example", null), 200);
            assertThat(details.path("name").asText()).isEqualTo("MV Kestrel Trader");
            assertThat(details.path("organizationName").asText()).isNotBlank();
            assertThat(body(call(get("/api/v1/vessels/" + kestrel + "/health"), "admin@seastella.example", null), 200)
                    .path("spares").asLong()).isPositive();

            JsonNode nordicList = body(call(get("/api/v1/vessels"), "coordinator.nordic@seastella.example", null), 200);
            assertThat(nordicList.toString()).doesNotContain("MV Kestrel Trader");
            assertThat(status(call(get("/api/v1/vessels/" + kestrel), "coordinator.nordic@seastella.example", null)))
                    .isEqualTo(404);
        }
    }

    @Test
    @DisplayName("an SBMC is issued, numbered in sequence, superseded, downloaded as issued and revoked")
    void sbmcLifecycle() throws Exception {
        String admin = "admin@seastella.example";
        JsonNode preview = body(call(get("/api/v1/vessels/" + kestrel + "/sbmc/preview"), admin, null), 200);
        assertThat(preview.path("missing")).isEmpty();
        LocalDate issue = LocalDate.parse(preview.path("issueDate").asText());
        assertThat(preview.path("expiryDate").asText()).isEqualTo(issue.plusYears(1).plusDays(1).toString());

        JsonNode first = body(call(post("/api/v1/vessels/" + kestrel + "/sbmc"), admin, Map.of(
                "issueDate", issue.toString(), "expiryDate", issue.plusYears(1).plusDays(1).toString(),
                "ownedBy", "Acme Ship Management Pte Ltd")), 201);
        String number = first.path("certificateNumber").asText();
        assertThat(number).matches("TM-\\d{4}-\\d{4}");
        assertThat(first.path("status").asText()).isEqualTo("ACTIVE");

        // Filed with the vessel's documents as a certificate, so its expiry is announced like any other.
        Map<String, Object> doc = jdbc.queryForMap("select document_type, certificate_number, expiry_date, "
                + "superseded_by_id, removed_at from document where id = ?", first.path("documentId").asLong());
        assertThat(doc.get("document_type")).isEqualTo("CERTIFICATE");
        assertThat(doc.get("certificate_number")).isEqualTo(number);

        // Downloaded exactly as issued.
        MvcResult pdf = call(get("/api/v1/certificates/" + first.path("id").asLong() + "/pdf"), admin, null);
        assertThat(status(pdf)).isEqualTo(200);
        try (PDDocument d = Loader.loadPDF(pdf.getResponse().getContentAsByteArray())) {
            String text = new PDFTextStripper().getText(d).replaceAll("\\s+", " ");
            assertThat(text).contains("Certificate No.: " + number).contains("MV. KESTREL TRADER").doesNotContain("MV. MV")
                    .contains("Acme Ship Management Pte Ltd");
        }

        // A second one takes the next number and supersedes the first, which is kept.
        JsonNode second = body(call(post("/api/v1/vessels/" + kestrel + "/sbmc"), admin,
                Map.of("issueDate", issue.toString())), 201);
        int seq1 = Integer.parseInt(number.substring(number.lastIndexOf('-') + 1));
        String number2 = second.path("certificateNumber").asText();
        assertThat(Integer.parseInt(number2.substring(number2.lastIndexOf('-') + 1))).isEqualTo(seq1 + 1);
        JsonNode list = body(call(get("/api/v1/vessels/" + kestrel + "/certificates"), admin, null), 200);
        assertThat(list).hasSize(2);
        assertThat(list.get(1).path("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(list.get(1).path("supersededBy").asText()).isEqualTo(number2);
        assertThat(jdbc.queryForObject("select superseded_by_id from document where id = ?", Long.class,
                first.path("documentId").asLong())).isEqualTo(second.path("documentId").asLong());

        // The filed copy cannot be removed or replaced as an ordinary upload.
        assertThat(status(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/documents/" + second.path("documentId").asLong()), admin, Map.of("reason", "tidy"))))
                .isEqualTo(409);

        // Revoking needs a reason; it stays on record and stops warning of its expiry.
        long id2 = second.path("id").asLong();
        assertThat(status(call(post("/api/v1/certificates/" + id2 + "/revoke"), admin, Map.of("reason", " "))))
                .isEqualTo(400);
        JsonNode revoked = body(call(post("/api/v1/certificates/" + id2 + "/revoke"), admin,
                Map.of("reason", "Issued with the wrong owner")), 200);
        assertThat(revoked.path("status").asText()).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("select removed_at is not null from document where id = ?", Boolean.class,
                second.path("documentId").asLong())).isTrue();

        // Every step is in the audit trail.
        for (String action : new String[]{"CERTIFICATE_GENERATED", "CERTIFICATE_SUPERSEDED",
                "CERTIFICATE_DOWNLOADED", "CERTIFICATE_REVOKED"}) {
            assertThat(jdbc.queryForObject("select count(*) from audit_entry where action = ? and vessel_id = ?",
                    Integer.class, action, kestrel)).as(action).isPositive();
        }
    }

    @Test
    @DisplayName("an SBMC is refused until the vessel record has what it prints")
    void missingParticularsBlockIssue() throws Exception {
        String admin = "admin@seastella.example";
        JsonNode v = body(call(get("/api/v1/vessels/" + bergen), admin, null), 200);
        Map<String, Object> form = new HashMap<>();
        form.put("name", v.path("name").asText());
        form.put("imoNumber", v.path("imoNumber").asText());
        form.put("mmsi", v.path("mmsi").asText(""));
        form.put("callSign", "");
        form.put("flag", v.path("flag").asText(""));
        body(call(put("/api/v1/vessels/" + bergen), admin, form), 200);

        JsonNode preview = body(call(get("/api/v1/vessels/" + bergen + "/sbmc/preview"), admin, null), 200);
        assertThat(preview.path("missing").toString()).contains("callSign");
        assertThat(status(call(post("/api/v1/vessels/" + bergen + "/sbmc"), admin,
                Map.of("issueDate", LocalDate.now().toString())))).isEqualTo(400);

        form.put("callSign", "LAZB5");
        body(call(put("/api/v1/vessels/" + bergen), admin, form), 200);
        assertThat(body(call(get("/api/v1/vessels/" + bergen + "/sbmc/preview"), admin, null), 200)
                .path("missing")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from audit_entry where action = 'VESSEL_UPDATED' "
                + "and entity_id = ?", Integer.class, bergen)).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("a pre-survey form prints the records, keeps what was typed over them, and is saved and audited")
    void preSurvey() throws Exception {
        String coordinator = "coordinator@seastella.example";
        JsonNode draft = body(call(get("/api/v1/vessels/" + kestrel + "/pre-survey/draft"), coordinator, null), 200);
        assertThat(draft.path("sections")).hasSize(6);
        assertThat(draft.path("sections").get(0).path("rows").get(0).path("cells").get(0).path("value").asText())
                .isEqualTo("MV Kestrel Trader");

        JsonNode saved = body(call(post("/api/v1/vessels/" + kestrel + "/pre-survey"), coordinator, Map.of(
                "values", Map.of("ClassRow1", "LR (typed)", "NotAField", "ignored"),
                "remarks", "Survey at Singapore, 4 Nov 2026")), 201);
        assertThat(saved.path("typedOver").asInt()).isEqualTo(1);
        assertThat(saved.path("generatedByRole").asText()).isEqualTo("SERVICE_COORDINATOR");

        MvcResult pdf = call(get("/api/v1/pre-survey/" + saved.path("id").asLong() + "/pdf"), coordinator, null);
        assertThat(status(pdf)).isEqualTo(200);
        try (PDDocument d = Loader.loadPDF(pdf.getResponse().getContentAsByteArray())) {
            assertThat(d.getNumberOfPages()).isEqualTo(2);
            String text = new PDFTextStripper().getText(d).replaceAll("\\s+", " ");
            assertThat(text).contains("MV Kestrel Trader").contains("LR (typed)")
                    .contains("Survey at Singapore, 4 Nov 2026").doesNotContain("ignored");
        }
        // Typing changed the form, never the vessel.
        assertThat(jdbc.queryForObject("select vessel_class from vessel where id = ?", String.class, kestrel))
                .isNotEqualTo("LR (typed)");
        assertThat(jdbc.queryForObject("select count(*) from audit_entry where action = 'PRE_SURVEY_FORM_GENERATED' "
                + "and vessel_id = ? and actor_role = 'SERVICE_COORDINATOR'", Integer.class, kestrel)).isPositive();
        assertThat(body(call(get("/api/v1/vessels/" + kestrel + "/pre-survey"), "admin@seastella.example", null), 200))
                .isNotEmpty();
    }

    @Test
    @DisplayName("a sphere replacement entered in the gyro's history reaches the form")
    void sphereReplacement() throws Exception {
        Long gyro = jdbc.queryForObject("select id from spare where vessel_id = ? and upper(name) like 'GYRO%' "
                + "order by path limit 1", Long.class, kestrel);
        LocalDate when = LocalDate.now().minusDays(40);
        body(call(post("/api/v1/spares/" + gyro + "/service-history"), "tech.head@acme-shipmanagement.example",
                Map.of("serviceDate", when.toString(), "workPerformed", "Gyro sphere replaced",
                        "kind", "SPHERE_REPLACEMENT")), 201);
        JsonNode draft = body(call(get("/api/v1/vessels/" + kestrel + "/pre-survey/draft"),
                "admin@seastella.example", null), 200);
        String expected = String.format("%02d/%02d", when.getMonthValue(), when.getYear() % 100);
        assertThat(draft.toString()).contains("\"key\":\"Date9_af_date.0.1\",\"label\":\"Sphere\",\"value\":\""
                + expected + "\"");
    }

    // ---------------------------------------------------------------- helpers

    private MvcResult call(MockHttpServletRequestBuilder request, String who, Object payload) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token(who));
        if (payload != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
        return mvc.perform(request).andReturn();
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private JsonNode body(MvcResult r, int expected) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String token(String email) throws Exception {
        String cached = tokens.get(email);
        if (cached != null) return cached;
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD)))).andReturn();
        String t = json.readTree(r.getResponse().getContentAsString()).path("accessToken").asText();
        tokens.put(email, t);
        return t;
    }
}
