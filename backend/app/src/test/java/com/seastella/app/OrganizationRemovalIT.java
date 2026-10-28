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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Deleting a client organization - asked for to clear a test organization off
 * the live platform. Acme is the busiest organization in the sample data, so
 * deleting it exercises every table a fleet touches, including the payments
 * and job logs that vessel deletion used to trip over.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "seastella.upload.storage-dir=./target/test-organization-removal",
        "spring.datasource.url=jdbc:h2:mem:seastella-org-removal-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@DisplayName("deleting an organization")
class OrganizationRemovalIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final String ADMIN = "admin@seastella.example";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("removes the organization, its fleet and its people; Thawe Marine's people and other clients stay")
    void deletesEverythingOfOneClient() throws Exception {
        long acme = jdbc.queryForObject("select id from organization where code = 'ACME'", Long.class);
        long nordic = jdbc.queryForObject("select id from organization where code <> 'ACME' order by id limit 1", Long.class);
        int nordicVessels = count("select count(*) from vessel where organization_id = ?", nordic);

        // Records vessel deletion used to stumble on: a payment and an engineer's log entry.
        long invoice = jdbc.queryForObject("select i.id from invoice i join vessel v on v.id = i.vessel_id "
                + "where v.organization_id = ? order by i.id limit 1", Long.class, acme);
        long coordinator = jdbc.queryForObject("select id from app_user where email = 'coordinator@seastella.example'", Long.class);
        jdbc.update("insert into invoice_payment (invoice_id, amount, received_on, method, recorded_by_user_id, created_at, version) "
                + "values (?, 100, current_date, 'BANK_TRANSFER', ?, current_timestamp, 0)", invoice, coordinator);
        Map<String, Object> job = jdbc.queryForMap("select r.id, r.vessel_id from service_request r join vessel v on v.id = r.vessel_id "
                + "where v.organization_id = ? order by r.id limit 1", acme);
        long engineer = jdbc.queryForObject("select id from app_user where role = 'SERVICE_ENGINEER' order by id limit 1", Long.class);
        jdbc.update("insert into job_log_entry (service_request_id, vessel_id, author_user_id, kind, occurred_at, created_at, version) "
                + "values (?, ?, ?, 'ARRIVED', current_timestamp, current_timestamp, 0)", job.get("id"), job.get("vessel_id"), engineer);

        // Only the Platform Administrator, and only with the name typed.
        assertThat(status(call(get("/api/v1/organizations/" + acme + "/removal"), "tech.head@acme-shipmanagement.example")))
                .isEqualTo(403);
        assertThat(status(call(delete("/api/v1/organizations/" + acme).param("confirm", "Acme"), ADMIN))).isEqualTo(400);

        JsonNode impact = body(call(get("/api/v1/organizations/" + acme + "/removal"), ADMIN), 200);
        assertThat(impact.path("vessels").asInt()).isPositive();
        assertThat(impact.path("people").asInt()).isPositive();
        assertThat(impact.path("serviceRequests").asInt()).isPositive();

        JsonNode removed = body(call(delete("/api/v1/organizations/" + acme)
                .param("confirm", impact.path("name").asText().toUpperCase()), ADMIN), 200);
        assertThat(removed.path("vessels").asInt()).isEqualTo(impact.path("vessels").asInt());

        // Acme is gone, with its fleet and its people.
        assertThat(count("select count(*) from organization where id = ?", acme)).isZero();
        assertThat(count("select count(*) from vessel where organization_id = ?", acme)).isZero();
        assertThat(count("select count(*) from app_user where organization_id = ?", acme)).isZero();
        assertThat(count("select count(*) from invoice_payment where invoice_id = ?", invoice)).isZero();
        assertThat(status(call(post("/api/v1/auth/login"), null, Map.of("email", "tech.head@acme-shipmanagement.example",
                "password", PASSWORD)))).isEqualTo(401);

        // Nordic, the Coordinator and the engineers are untouched; the Coordinator now serves Nordic only.
        assertThat(count("select count(*) from vessel where organization_id = ?", nordic)).isEqualTo(nordicVessels);
        assertThat(count("select count(*) from app_user where id = ?", coordinator)).isEqualTo(1);
        assertThat(count("select count(*) from user_organization_assignment where user_id = ?", coordinator)).isEqualTo(1);
        assertThat(count("select count(*) from app_user where id = ?", engineer)).isEqualTo(1);
        assertThat(status(call(get("/api/v1/dashboards/service-coordinator"), "coordinator@seastella.example"))).isEqualTo(200);

        // The deletion is in the audit trail, which is kept.
        assertThat(count("select count(*) from audit_entry where action = 'ORGANIZATION_DELETED' and entity_id = ?", acme))
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String who) throws Exception {
        return call(request, who, null);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String who, Object payload) throws Exception {
        if (who != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token(who));
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
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD)))).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("accessToken").asText();
    }
}
