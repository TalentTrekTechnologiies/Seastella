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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-register-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("the invoice, engineer and job-history registers")
class WorkRegisterIT {

    private static final String PASSWORD = "SeaStella#Demo2026";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private String shipManager;
    private String coordinator;
    private String captain;
    private String engineer;
    private String admin;

    @BeforeAll
    void setUp() throws Exception {
        shipManager = token("d.fernandes@acme-shipmanagement.example");
        coordinator = token("coordinator@seastella.example");
        captain = token("master.kestrel@acme-shipmanagement.example");
        engineer = token("t.okafor@marine-electronics.example");
        admin = token("admin@seastella.example");
    }

    @Test
    @DisplayName("invoices: each money role sees its own vessels' invoices; the Captain and engineer see none")
    void invoices() throws Exception {
        JsonNode register = body(get("/api/v1/invoices", shipManager), 200);
        assertThat(register.path("canDecide").asBoolean()).isTrue();
        assertThat(register.path("invoices").size()).isGreaterThan(0);

        // Only the Ship Manager's own vessels.
        Set<Long> own = new HashSet<>(jdbc.queryForList("""
                select a.vessel_id from user_vessel_assignment a join app_user u on u.id = a.user_id
                where u.email = 'd.fernandes@acme-shipmanagement.example'""", Long.class));
        register.path("invoices").forEach(i ->
                assertThat(own).as("invoice on an assigned vessel").contains(i.path("vesselId").asLong()));

        // Totals per status add up to the list.
        long counted = 0;
        for (JsonNode t : register.path("totals")) counted += t.path("count").asLong();
        assertThat(counted).isEqualTo(register.path("invoices").size());

        // The Coordinator sees them but does not decide; the admin sees every one.
        assertThat(body(get("/api/v1/invoices", coordinator), 200).path("canDecide").asBoolean()).isFalse();
        long all = jdbc.queryForObject("select count(*) from invoice", Long.class);
        assertThat(body(get("/api/v1/invoices", admin), 200).path("invoices").size()).isEqualTo((int) all);

        // Money is never shown to the Captain or the Service Engineer (SoW s8).
        assertThat(get("/api/v1/invoices", captain).getResponse().getStatus()).isEqualTo(403);
        assertThat(get("/api/v1/invoices", engineer).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("engineers: the Coordinator sees each engineer's current jobs on their vessels")
    void engineers() throws Exception {
        JsonNode list = body(get("/api/v1/engineers/workload", coordinator), 200);
        JsonNode okafor = null;
        for (JsonNode e : list) {
            if ("t.okafor@marine-electronics.example".equals(e.path("email").asText())) okafor = e;
        }
        assertThat(okafor).as("the engineer is listed").isNotNull();

        long active = jdbc.queryForObject("""
                select count(*) from service_request r join app_user u on u.id = r.assigned_engineer_user_id
                where u.email = 't.okafor@marine-electronics.example'
                  and r.status in ('ENGINEER_ASSIGNED', 'IN_PROGRESS')""", Long.class);
        assertThat(okafor.path("activeJobs").size()).isEqualTo((int) active);

        assertThat(get("/api/v1/engineers/workload", shipManager).getResponse().getStatus()).isEqualTo(403);
        assertThat(get("/api/v1/engineers/workload", engineer).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("job history: an engineer's finished jobs, with the work reported and never the cost")
    void jobHistory() throws Exception {
        MvcResult r = get("/api/v1/jobs/history", engineer);
        JsonNode history = body(r, 200);
        long finished = jdbc.queryForObject("""
                select count(*) from service_request r join app_user u on u.id = r.assigned_engineer_user_id
                where u.email = 't.okafor@marine-electronics.example'
                  and r.status in ('COMPLETION_REPORTED', 'COMPLETED')""", Long.class);
        assertThat(history.size()).isEqualTo((int) finished);

        String raw = r.getResponse().getContentAsString();
        assertThat(raw).doesNotContain("finalCost").doesNotContain("amount").doesNotContain("costVariance");

        assertThat(get("/api/v1/jobs/history", coordinator).getResponse().getStatus()).isEqualTo(403);
    }

    private MvcResult get(String path, String token) throws Exception {
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
