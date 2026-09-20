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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The activity feed is pushed rather than polled (FEE-04).
 *
 * <p>Two things are worth proving: that the stream is the Platform Admin's
 * alone, like the feed it carries, and that a client which has been told
 * something happened can ask for exactly what it has not seen. Between them
 * they are the whole mechanism — the server announces an id, the client reads
 * everything after the id it holds.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-stream-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("the activity stream")
class ActivityStreamIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final String ADMIN = "admin@seastella.example";
    private static final String HEAD = "tech.head@acme-shipmanagement.example";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private String admin;
    private String head;

    @BeforeAll
    void setUp() throws Exception {
        admin = token(ADMIN);
        head = token(HEAD);
    }

    @Test
    @DisplayName("the stream opens for the Platform Admin and for nobody else (FEE-04)")
    void streamIsAdminOnly() throws Exception {
        MvcResult opened = mvc.perform(get("/api/v1/activity/stream")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andReturn();

        assertThat(opened.getResponse().getStatus()).isEqualTo(200);
        assertThat(opened.getRequest().isAsyncStarted()).as("held open, not answered and closed").isTrue();
        // The first frame goes out with the response, so a browser sees the
        // connection confirmed rather than sitting in "connecting".
        assertThat(opened.getResponse().getContentAsString()).contains("event:open");

        assertThat(mvc.perform(get("/api/v1/activity/stream")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn()
                .getResponse().getStatus()).isEqualTo(403);

        assertThat(mvc.perform(get("/api/v1/activity/stream")).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("after an announcement, the feed returns only what is newer (FEE-04)")
    void feedReturnsOnlyWhatIsNew() throws Exception {
        JsonNode before = body(fetch("/api/v1/activity", admin));
        long newest = before.path("items").get(0).path("id").asLong();

        // Nothing has happened since: asking for what is newer returns nothing.
        assertThat(body(fetch("/api/v1/activity?after=" + newest, admin)).path("items")).isEmpty();

        // Something happens that the feed carries — a vessel is created.
        long organizationId = jdbc.queryForObject(
                "select id from organization order by id limit 1", Long.class);
        // 9600009: a real IMO number, check digit and all - the API refuses
        // anything else, which is the point of IMP-04.
        MvcResult created = mvc.perform(post("/api/v1/vessels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "organizationId", organizationId,
                        "name", "MV Stream Test",
                        "imoNumber", "9600009"))))
                .andReturn();
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString()).isEqualTo(201);

        JsonNode fresh = body(fetch("/api/v1/activity?after=" + newest, admin));
        assertThat(fresh.path("items")).as("the new entry, and only it").hasSize(1);
        assertThat(fresh.path("items").get(0).path("id").asLong()).isGreaterThan(newest);
        assertThat(fresh.path("items").get(0).path("summary").asText()).contains("MV Stream Test");

        // An audit entry was really written for it: the announcement follows a
        // row, not the other way round.
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_entry where action = 'VESSEL_CREATED'", Integer.class))
                .isPositive();
    }

    private MvcResult fetch(String path, String token) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String token(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD)))).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).path("accessToken").asText();
    }
}
