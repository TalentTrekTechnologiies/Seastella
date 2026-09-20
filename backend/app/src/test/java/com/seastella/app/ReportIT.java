package com.seastella.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The SoW s7 reports (RPT-01 to RPT-09): each one builds, each is scoped to the
 * caller, cost reports are not offered to the roles that may not see cost, and
 * every report exports as a PDF.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-report-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@DisplayName("reports")
class ReportIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final List<String> ALL_REPORTS = List.of("vessel-spares", "service-due", "certificates",
            "troubleshooting", "invoices", "fleet-summary", "parts-inventory");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    @DisplayName("every report builds for a Technical Head, with columns, rows and a scope note")
    void allReportsBuild() throws Exception {
        String head = token("tech.head@acme-shipmanagement.example");

        JsonNode available = body(fetch("/api/v1/reports", head), 200);
        assertThat(available.findValuesAsText("key")).containsExactlyInAnyOrderElementsOf(ALL_REPORTS);

        for (String key : ALL_REPORTS) {
            JsonNode report = body(fetch("/api/v1/reports/" + key, head), 200);
            assertThat(report.path("title").asText()).as(key).isNotBlank();
            assertThat(report.path("columns")).as(key).isNotEmpty();
            assertThat(report.path("generatedBy").asText()).as(key).isNotBlank();
            assertThat(report.path("scopeNote").asText()).as(key).contains("vessel");
            // Rows are as wide as the columns say, or the PDF would misalign.
            int columns = report.path("columns").size();
            report.path("rows").forEach(row -> assertThat(row.size()).as(key).isEqualTo(columns));
        }
    }

    @Test
    @DisplayName("SoW §12: cost reports are not offered to a Captain, and are refused if asked for")
    void captainCannotSeeCost() throws Exception {
        String captain = token("master.kestrel@acme-shipmanagement.example");

        JsonNode available = body(fetch("/api/v1/reports", captain), 200);
        assertThat(available.findValuesAsText("key")).contains("vessel-spares", "service-due", "certificates")
                .doesNotContain("invoices");

        assertThat(mvc.perform(get("/api/v1/reports/invoices")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + captain)).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        assertThat(mvc.perform(get("/api/v1/reports/invoices/pdf")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + captain)).andReturn().getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("RPT-09: a report holds only the rows the caller's scope allows")
    void reportsAreScoped() throws Exception {
        String head = token("tech.head@acme-shipmanagement.example");
        String manager = token("d.fernandes@acme-shipmanagement.example");
        String captain = token("master.kestrel@acme-shipmanagement.example");

        int fleetRows = body(fetch("/api/v1/reports/fleet-summary", head), 200).path("rows").size();
        int managerRows = body(fetch("/api/v1/reports/fleet-summary", manager), 200).path("rows").size();
        assertThat(managerRows).isPositive().isLessThan(fleetRows);

        JsonNode captainSpares = body(fetch("/api/v1/reports/vessel-spares", captain), 200);
        java.util.Set<String> vessels = new java.util.HashSet<>();
        captainSpares.path("rows").forEach(row -> vessels.add(row.get(0).asText()));
        assertThat(vessels).as("a Captain's equipment report covers one vessel").hasSize(1);
        assertThat(captainSpares.path("scopeNote").asText()).isEqualTo("One vessel");
    }

    @Test
    @DisplayName("a report downloads as a PDF that says what it is")
    void pdfExport() throws Exception {
        String head = token("tech.head@acme-shipmanagement.example");
        MvcResult pdf = mvc.perform(get("/api/v1/reports/service-due/pdf")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn();

        assertThat(pdf.getResponse().getStatus()).isEqualTo(200);
        assertThat(pdf.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(pdf.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment", "seastella-service-due-");
        byte[] content = pdf.getResponse().getContentAsByteArray();
        assertThat(new String(content, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(content.length).isGreaterThan(1_000);
    }

    @Test
    @DisplayName("an unknown report is a 404, not an empty page")
    void unknownReport() throws Exception {
        assertThat(mvc.perform(get("/api/v1/reports/not-a-report")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("admin@seastella.example")))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- helpers

    private MvcResult fetch(String path, String token) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
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
