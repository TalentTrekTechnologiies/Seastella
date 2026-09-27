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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "seastella.upload.storage-dir=./target/test-joblog",
        "spring.datasource.url=jdbc:h2:mem:seastella-joblog-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("the engineer's job log")
class JobLogIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private String engineer;
    private String otherEngineer;
    private String coordinator;
    private String captain;
    private long requestId;

    @BeforeAll
    void setUp() throws Exception {
        engineer = token("t.okafor@marine-electronics.example");
        otherEngineer = token("s.nakamura@marine-electronics.example");
        coordinator = token("coordinator@seastella.example");
        // A job assigned to this engineer and not yet started.
        requestId = jdbc.queryForObject("""
                select r.id from service_request r join app_user u on u.id = r.assigned_engineer_user_id
                where u.email = 't.okafor@marine-electronics.example' and r.status = 'ENGINEER_ASSIGNED'
                order by r.id limit 1""", Long.class);
        String captainEmail = jdbc.queryForObject("""
                select u.email from app_user u join user_vessel_assignment a on a.user_id = u.id
                join service_request r on r.vessel_id = a.vessel_id
                where r.id = ? and u.role = 'CAPTAIN' limit 1""", String.class, requestId);
        captain = token(captainEmail);
    }

    @Test
    @DisplayName("from arriving aboard to finishing, each step is logged with its time; others read it")
    void wholeJob() throws Exception {
        String path = "/api/v1/service-requests/" + requestId + "/job-log";
        assertThat(body(get(path, engineer), 200).path("canWrite").asBoolean()).isTrue();

        Instant arrived = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        add(engineer, "ARRIVED", arrived.toString(), "Aboard at berth 4.", null, 201);

        // "Work started" from the log starts the job, at the time given.
        Instant started = arrived.plus(20, ChronoUnit.MINUTES);
        add(engineer, "STARTED", started.toString(), null, null, 201);
        assertThat(jdbc.queryForObject("select status from service_request where id = ?", String.class, requestId))
                .isEqualTo("IN_PROGRESS");
        add(engineer, "STARTED", null, null, null, 409);                     // only once

        add(engineer, "UPDATE", null, "Fan replaced, unit powered up.", PNG, 201);
        add(engineer, "UPDATE", null, null, null, 400);                     // an update says what was done
        add(engineer, "WAITING", null, "Waiting for the shore test set.", null, 201);
        add(engineer, "RESUMED", null, null, null, 201);
        add(engineer, "FINISHED", null, "Tested against the maker's procedure.", null, 201);
        add(engineer, "FINISHED", Instant.now().plus(1, ChronoUnit.DAYS).toString(), null, null, 400);

        JsonNode log = body(get(path, coordinator), 200);
        List<String> kinds = new ArrayList<>();
        log.path("entries").forEach(e -> kinds.add(e.path("kind").asText()));
        assertThat(kinds).containsExactly("ARRIVED", "STARTED", "UPDATE", "WAITING", "RESUMED", "FINISHED");
        assertThat(log.path("entries").get(0).path("occurredAt").asText()).startsWith(arrived.toString().substring(0, 16));
        assertThat(log.path("entries").get(1).path("automatic").asBoolean())
                .as("one start entry, the engineer's own, not a second automatic one").isFalse();
        assertThat(log.path("entries").get(1).path("occurredAt").asText()).startsWith(started.toString().substring(0, 16));
        assertThat(log.path("entries").get(2).path("photo").path("image").asBoolean()).isTrue();
        assertThat(log.path("canWrite").asBoolean()).as("the Coordinator reads it").isFalse();

        // The Captain, who had the engineer aboard, reads it too; nobody else writes in it.
        assertThat(body(get(path, captain), 200).path("entries").size()).isEqualTo(6);
        add(coordinator, "UPDATE", null, "Not mine to write", null, 403);
        MvcResult other = addResult(otherEngineer, "UPDATE", null, "Not my job", null);
        assertThat(other.getResponse().getStatus()).isIn(403, 404);
    }

    private void add(String token, String kind, String occurredAt, String note, byte[] file, int expected)
            throws Exception {
        MvcResult r = addResult(token, kind, occurredAt, note, file);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
    }

    private MvcResult addResult(String token, String kind, String occurredAt, String note, byte[] file)
            throws Exception {
        var request = multipart("/api/v1/service-requests/" + requestId + "/job-log");
        if (file != null) request.file(new org.springframework.mock.web.MockMultipartFile("file", "fan.png", null, file));
        request.param("kind", kind);
        if (occurredAt != null) request.param("occurredAt", occurredAt);
        if (note != null) request.param("note", note);
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
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
