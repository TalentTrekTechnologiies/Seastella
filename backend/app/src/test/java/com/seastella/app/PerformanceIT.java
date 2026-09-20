package com.seastella.app;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Dashboards stay responsive at pilot volume (NFR-01).
 *
 * <p>A dashboard is the screen every role opens first, and the one that reads
 * the most: fleet health across every hull, due dates across every tracked
 * spare, open requests, alerts. This times each of the six against the seeded
 * fleet and holds them to a budget.
 *
 * <p>The budget is deliberately loose — it is there to catch a query that has
 * become quadratic or an N+1 that crept in, not to measure the machine. A
 * dashboard that has slipped from 80 ms to 800 ms has a defect worth finding;
 * one that takes 300 ms on a laptop running a test suite has not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-performance-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("dashboards at pilot volume")
class PerformanceIT {

    /** Per dashboard, after warm-up. Generous: this is a regression guard, not a benchmark. */
    private static final long BUDGET_MS = 1_500;

    private static final String PASSWORD = "SeaStella#Demo2026";

    private record Dashboard(String endpoint, String email) {}

    private static final List<Dashboard> DASHBOARDS = List.of(
            new Dashboard("platform-admin", "admin@seastella.example"),
            new Dashboard("technical-head", "tech.head@acme-shipmanagement.example"),
            new Dashboard("ship-manager", "d.fernandes@acme-shipmanagement.example"),
            new Dashboard("captain", "master.kestrel@acme-shipmanagement.example"),
            new Dashboard("service-coordinator", "coordinator@seastella.example"),
            new Dashboard("service-engineer", "t.okafor@marine-electronics.example"));

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private final Map<String, String> tokens = new java.util.LinkedHashMap<>();

    @BeforeAll
    void signIn() throws Exception {
        for (Dashboard d : DASHBOARDS) tokens.put(d.endpoint(), token(d.email()));
    }

    @Test
    @DisplayName("every dashboard answers inside its budget, five times running (NFR-01)")
    void dashboardsAreResponsive() throws Exception {
        // The volume the budget is being held against, stated in the failure.
        String volume = "%d vessels, %d spares, %d requests, %d maintenance rules".formatted(
                jdbc.queryForObject("select count(*) from vessel", Integer.class),
                jdbc.queryForObject("select count(*) from spare", Integer.class),
                jdbc.queryForObject("select count(*) from service_request", Integer.class),
                jdbc.queryForObject("select count(*) from spare_maintenance_rule", Integer.class));
        List<String> slow = new ArrayList<>();

        for (Dashboard d : DASHBOARDS) {
            String token = tokens.get(d.endpoint());
            call(d.endpoint(), token);                       // warm the context and the caches

            long worst = 0;
            for (int i = 0; i < 5; i++) {
                long started = System.nanoTime();
                MvcResult result = call(d.endpoint(), token);
                long took = (System.nanoTime() - started) / 1_000_000;
                assertThat(result.getResponse().getStatus()).as(d.endpoint()).isEqualTo(200);
                worst = Math.max(worst, took);
            }
            if (worst > BUDGET_MS) slow.add(d.endpoint() + " took " + worst + " ms");
        }

        assertThat(slow)
                .as("dashboards slower than " + BUDGET_MS + " ms at " + volume)
                .isEmpty();
    }

    @Test
    @DisplayName("a dashboard is a bounded number of queries, not one per row (NFR-01)")
    void dashboardsDoNotScaleWithRows() throws Exception {
        // An N+1 shows up as a response whose time grows with the fleet rather
        // than staying flat. The Technical Head reads the whole fleet; the
        // Captain reads one vessel. If the fleet-wide one is not within an
        // order of magnitude of the single-vessel one, something is per-row.
        long fleet = timed("technical-head");
        long vessel = timed("captain");

        assertThat(fleet)
                .as("fleet dashboard %d ms against single-vessel %d ms: suspect a query per row", fleet, vessel)
                .isLessThanOrEqualTo(Math.max(vessel * 10, 400));
    }

    private long timed(String endpoint) throws Exception {
        String token = tokens.get(endpoint);
        call(endpoint, token);
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long started = System.nanoTime();
            call(endpoint, token);
            best = Math.min(best, (System.nanoTime() - started) / 1_000_000);
        }
        return best;
    }

    private MvcResult call(String endpoint, String token) throws Exception {
        return mvc.perform(get("/api/v1/dashboards/" + endpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private String token(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD)))).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("accessToken").asText();
    }
}
