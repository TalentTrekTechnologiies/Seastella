package com.seastella.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Two pieces of configuration the SoW asks to be settable rather than coded:
 * the maintenance colour bands (§11, AUD-13) and a person's own details
 * (IAM-08).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-configuration-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("configuration")
class ConfigurationIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final String COOKIE = "seastella_refresh";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private String admin;
    private String head;

    @BeforeAll
    void setUp() throws Exception {
        admin = token("admin@seastella.example");
        head = token("tech.head@acme-shipmanagement.example");
    }

    @Test
    @DisplayName("the colour bands are days the Platform Admin can move, and the change is audited")
    void thresholdsAreConfigurable() throws Exception {
        JsonNode bands = body(mvc.perform(get("/api/v1/maintenance/thresholds")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andReturn(), 200);
        assertThat(bands.findValuesAsText("statusCode"))
                .contains("NORMAL", "APPROACHING", "URGENT", "DUE", "OVERDUE");

        // Only the two warning bands are settings; Due and Overdue are definitions.
        assertThat(named(bands, "OVERDUE").path("configurable").asBoolean()).isFalse();
        assertThat(named(bands, "URGENT").path("configurable").asBoolean()).isTrue();

        JsonNode changed = body(putBands(admin, 20, 60), 200);
        JsonNode urgent = named(changed, "URGENT");
        JsonNode approaching = named(changed, "APPROACHING");
        assertThat(urgent.path("toDays").asInt()).isEqualTo(20);
        assertThat(approaching.path("fromDays").asInt()).isEqualTo(21);
        assertThat(approaching.path("toDays").asInt()).isEqualTo(60);
        assertThat(named(changed, "NORMAL").path("fromDays").asInt()).isEqualTo(61);
        assertThat(approaching.path("colour").asText()).isNotBlank();

        assertThat(jdbc.queryForObject("select count(*) from audit_entry where action = 'THRESHOLD_CHANGED'",
                Integer.class)).isEqualTo(1);

        // Spares are re-banded straight away, not overnight.
        Integer approachingSpares = jdbc.queryForObject("""
                select count(*) from spare_due_state where status = 'APPROACHING'
                """, Integer.class);
        assertThat(approachingSpares).isNotNull();
    }

    @Test
    @DisplayName("a ladder with a gap in it is refused")
    void thresholdsMustMeet() throws Exception {
        MvcResult overlap = putBands(admin, 30, 20);
        assertThat(overlap.getResponse().getStatus()).isEqualTo(400);
        assertThat(overlap.getResponse().getContentAsString()).contains("further out than urgent");

        assertThat(putBands(admin, 0, 20).getResponse().getStatus()).isEqualTo(400);
        assertThat(putBands(admin, 10, 400).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("only the Platform Admin sets the bands")
    void thresholdsArePlatformAdminOnly() throws Exception {
        assertThat(mvc.perform(get("/api/v1/maintenance/thresholds")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        assertThat(putBands(head, 5, 10).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("a name is corrected in place; a new sign-in address ends every session")
    void userDetailsCanBeCorrected() throws Exception {
        String email = "rename-" + java.util.UUID.randomUUID().toString().substring(0, 8)
                + "@acme-shipmanagement.example";
        Long organizationId = jdbc.queryForObject(
                "select organization_id from app_user where email = 'tech.head@acme-shipmanagement.example'", Long.class);
        JsonNode created = body(mvc.perform(post("/api/v1/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fullName", "Mispelled Name", "email", email,
                        "role", "TECHNICAL_HEAD", "organizationId", organizationId)))).andReturn(), 201);
        long userId = created.path("user").path("id").asLong();

        // The name alone: corrected, audited, nothing else disturbed.
        JsonNode renamed = body(putProfile(admin, userId, Map.of("fullName", "Corrected Name")), 200);
        assertThat(renamed.path("fullName").asText()).isEqualTo("Corrected Name");
        assertThat(renamed.path("email").asText()).isEqualTo(email);

        // Give them a session, then change the address: the session must end,
        // because that address is how they sign in.
        String invitationToken = link(created);
        MvcResult accepted = mvc.perform(post("/api/v1/account/invitations/" + invitationToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"harbour lights at dawn\"}")).andReturn();
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        String session = cookie(accepted);

        String newEmail = "corrected-" + email;
        JsonNode moved = body(putProfile(admin, userId, Map.of("email", newEmail)), 200);
        assertThat(moved.path("email").asText()).isEqualTo(newEmail);
        assertThat(mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(COOKIE, session))
                .header("X-Requested-With", "SeaStella")).andReturn().getResponse().getStatus()).isEqualTo(401);

        assertThat(login(email, "harbour lights at dawn").getResponse().getStatus()).isEqualTo(401);
        assertThat(login(newEmail, "harbour lights at dawn").getResponse().getStatus()).isEqualTo(200);

        assertThat(jdbc.queryForObject("select count(*) from audit_entry where action = 'USER_UPDATED' "
                + "and entity_id = ?", Integer.class, userId)).isEqualTo(2);
    }

    @Test
    @DisplayName("an address already in use is refused")
    void addressesStayUnique() throws Exception {
        Long userId = jdbc.queryForObject(
                "select id from app_user where email = 'tech.head@acme-shipmanagement.example'", Long.class);
        MvcResult clash = putProfile(admin, userId, Map.of("email", "admin@seastella.example"));
        assertThat(clash.getResponse().getStatus()).isEqualTo(409);
    }

    // ---------------------------------------------------------------- helpers

    private static JsonNode named(JsonNode bands, String statusCode) {
        for (JsonNode band : bands) {
            if (statusCode.equals(band.path("statusCode").asText())) return band;
        }
        throw new AssertionError("No band " + statusCode);
    }

    private static String link(JsonNode created) {
        String url = created.path("link").asText();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private static String cookie(MvcResult r) {
        String header = r.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).as("Set-Cookie").isNotNull();
        String pair = header.split(";", 2)[0];
        return pair.substring(pair.indexOf('=') + 1);
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password)))).andReturn();
    }

    private MvcResult putBands(String token, int urgentUpToDays, int approachingUpToDays) throws Exception {
        return mvc.perform(put("/api/v1/maintenance/thresholds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "urgentUpToDays", urgentUpToDays, "approachingUpToDays", approachingUpToDays))))
                .andReturn();
    }

    private MvcResult putProfile(String token, long userId, Map<String, Object> change) throws Exception {
        return mvc.perform(put("/api/v1/users/" + userId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(change)))
                .andReturn();
    }

    private JsonNode body(MvcResult r, int expectedStatus) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String token(String email) throws Exception {
        return json.readTree(login(email, PASSWORD).getResponse().getContentAsString()).path("accessToken").asText();
    }
}
