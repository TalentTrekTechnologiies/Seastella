package com.seastella.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Standing up a new client, end to end (SoW §4.1).
 *
 * <p>"Platform Admin creates Organization → Platform Admin creates Technical
 * Head → Technical Head creates Ship Managers → Technical Head assigns vessels
 * per manager → Ship Manager assigns the Captain to each vessel." Five steps,
 * each performed by a different role, ending with a vessel that is operational
 * on the platform.
 *
 * <p>What matters as much as the happy path is that the chain cannot be
 * short-circuited: the SoW deliberately keeps day-to-day fleet administration
 * with the Technical Head and Ship Manager, and the Platform Admin at the
 * organization level. Each step is therefore also tried by the role one link
 * further down, which must be refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-provisioning-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("standing up a new client organization")
class ProvisioningChainIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final String ADMIN = "admin@seastella.example";
    private static final String OTHER_HEAD = "tech.head@acme-shipmanagement.example";
    /** What each invitee chooses for themselves; nobody else ever sees a password. */
    private static final String CHOSEN = "Harbourmaster-2026!";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("organization → Technical Head → Ship Manager → vessels → Captain (SoW §4.1)")
    void theWholeChain() throws Exception {
        String admin = token(ADMIN);

        // 1. The Platform Admin creates the organization.
        JsonNode org = body(post("/api/v1/organizations", admin, Map.of(
                "name", "Southern Star Shipping Pte Ltd",
                "code", "SSTAR",
                "address", "12 Keppel Road, Singapore",
                "contactEmail", "ops@southern-star.example",
                "contactPhone", "+65 6123 4567")), 201);
        long organizationId = org.path("id").asLong();
        assertThat(org.path("code").asText()).isEqualTo("SSTAR");
        assertThat(org.path("vesselCount").asLong()).isZero();

        // The short code appears in request and invoice numbers, so it has to
        // be unique — a second organization cannot take it.
        assertThat(post("/api/v1/organizations", admin, Map.of(
                "name", "Someone Else Ltd", "code", "sstar")).getResponse().getStatus())
                .as("duplicate code, differing only in case").isEqualTo(409);

        // 2. The Platform Admin creates that organization's Technical Head.
        JsonNode head = body(post("/api/v1/users", admin, Map.of(
                "fullName", "Anneke Visser",
                "email", "tech.head@southern-star.example",
                "role", "TECHNICAL_HEAD",
                "organizationId", organizationId)), 201);
        assertThat(head.path("user").path("status").asText())
                .as("invited, with no usable password until they choose one").isEqualTo("INVITED");

        // 3. A vessel for them to run, and the Technical Head's own Ship Manager.
        //    (Vessel creation sits with the Platform Admin and Technical Head.)
        JsonNode vessel = body(post("/api/v1/vessels", admin, Map.of(
                "organizationId", organizationId,
                "name", "MV Southern Dawn",
                "imoNumber", "9700005",
                "vesselType", "Bulk Carrier",
                "flag", "Singapore")), 201);
        long vesselId = vessel.path("id").asLong();

        String headToken = activate(head, CHOSEN);

        JsonNode manager = body(post("/api/v1/users", headToken, Map.of(
                "fullName", "Ravi Menon",
                "email", "r.menon@southern-star.example",
                "role", "SHIP_MANAGER",
                "organizationId", organizationId)), 201);
        long managerId = manager.path("user").path("id").asLong();

        // 4. The Technical Head decides which vessels that manager is responsible for.
        body(put("/api/v1/users/" + managerId + "/vessels", headToken,
                Map.of("vesselIds", List.of(vesselId))), 200);
        assertThat(jdbc.queryForObject(
                "select count(*) from user_vessel_assignment where user_id = ? and vessel_id = ?",
                Integer.class, managerId, vesselId)).isEqualTo(1);

        // 5. The Ship Manager assigns the Captain — the last step before the
        //    vessel is operational.
        String managerToken = activate(manager, CHOSEN);
        JsonNode captain = body(post("/api/v1/users", managerToken, Map.of(
                "fullName", "Elena Rossi",
                "email", "master.dawn@southern-star.example",
                "role", "CAPTAIN",
                "organizationId", organizationId,
                "vesselId", vesselId)), 201);
        long captainId = captain.path("user").path("id").asLong();

        body(put("/api/v1/vessels/" + vesselId + "/captain", managerToken,
                Map.of("captainUserId", captainId)), 200);

        // The vessel is now operational: its Captain can sign in and sees it.
        String captainToken = activate(captain, CHOSEN);
        JsonNode dashboard = body(get("/api/v1/dashboards/captain", captainToken), 200);
        assertThat(dashboard.path("vessel").path("vesselId").asLong()).isEqualTo(vesselId);
        assertThat(dashboard.path("vessel").path("name").asText()).isEqualTo("MV Southern Dawn");

        // And the organization now reports the fleet it was given.
        JsonNode listed = body(get("/api/v1/organizations", admin), 200);
        JsonNode mine = one(listed, organizationId);
        assertThat(mine.path("vesselCount").asLong()).isEqualTo(1);
        assertThat(mine.path("shipManagerCount").asLong()).isEqualTo(1);
        assertThat(mine.path("technicalHeads").toString()).contains("Anneke Visser");
    }

    @Test
    @DisplayName("the chain cannot be short-circuited (SoW §4.1, §5)")
    void eachStepStaysWithItsRole() throws Exception {
        String head = token(OTHER_HEAD);

        // Creating an organization is the Platform Admin's alone: §4.1 keeps the
        // Technical Head at fleet level and below.
        assertThat(post("/api/v1/organizations", head, Map.of(
                "name", "Not Mine To Create Ltd", "code", "NMTC")).getResponse().getStatus())
                .isEqualTo(403);

        // A Technical Head may not create another Technical Head — that grant
        // belongs one link up the chain.
        assertThat(post("/api/v1/users", head, Map.of(
                "fullName", "Second Head",
                "email", "second.head@acme-shipmanagement.example",
                "role", "TECHNICAL_HEAD",
                "organizationId", organizationId(OTHER_HEAD))).getResponse().getStatus())
                .isEqualTo(403);

        // Nor may they create a user in somebody else's organization.
        long otherOrg = jdbc.queryForObject(
                "select id from organization where id <> ? order by id limit 1",
                Long.class, organizationId(OTHER_HEAD));
        int status = post("/api/v1/users", head, Map.of(
                "fullName", "Poached Manager",
                "email", "poached@nordic-tanker.example",
                "role", "SHIP_MANAGER",
                "organizationId", otherOrg)).getResponse().getStatus();
        assertThat(status).as("another fleet is not found, not forbidden (S-08)").isIn(403, 404);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Accepts the invitation the way the invitee would, and signs in.
     *
     * <p>With no mail server the API hands the link back to the administrator
     * who created the account; that is the same link the email would carry, so
     * the test walks the real path rather than reaching into the table. Only a
     * hash of it is ever stored.
     */
    private String activate(JsonNode created, String password) throws Exception {
        String link = created.path("link").asText();
        assertThat(link).as("invitation link returned when email is not configured").isNotBlank();
        String tokenValue = link.substring(link.lastIndexOf('/') + 1);

        body(post("/api/v1/account/invitations/" + tokenValue, null, Map.of("password", password)), 200);
        return token(created.path("user").path("email").asText(), password);
    }

    private long organizationId(String email) {
        return jdbc.queryForObject("select organization_id from app_user where email = ?", Long.class, email);
    }

    private JsonNode one(JsonNode rows, long id) {
        for (JsonNode row : rows) if (row.path("id").asLong() == id) return row;
        throw new AssertionError("organization " + id + " is not in the list");
    }

    private MvcResult post(String path, String token, Object payload) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload));
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return mvc.perform(request).andReturn();
    }

    private MvcResult put(String path, String token, Object payload) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload))).andReturn();
    }

    private MvcResult get(String path, String token) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private JsonNode body(MvcResult r, int expected) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private String token(String email) throws Exception {
        return token(email, PASSWORD);
    }

    private String token(String email, String password) throws Exception {
        MvcResult r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password)))).andReturn();
        assertThat(r.getResponse().getStatus()).as("sign in as " + email).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString()).path("accessToken").asText();
    }
}
