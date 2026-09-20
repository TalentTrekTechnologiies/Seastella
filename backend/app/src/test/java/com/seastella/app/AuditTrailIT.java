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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The audit trail, in full (SoW §8.5, §12).
 *
 * <p>§8.5 asks for "full audit-trail access (not limited to configuration
 * actions)". The activity feed reads the same table but deliberately leaves
 * things out, so the requirement is only met if there is somewhere the omitted
 * rows can still be seen. That is what is proved here: a sign-in is in the
 * trail and not in the feed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-audit-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("the audit trail")
class AuditTrailIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final String ADMIN = "admin@seastella.example";
    private static final String HEAD = "tech.head@acme-shipmanagement.example";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    @DisplayName("holds what the activity feed leaves out (SoW §8.5)")
    void holdsWhatTheFeedOmits() throws Exception {
        // Signing in is itself the event under test.
        String admin = token(ADMIN);

        JsonNode trail = body(fetch("/api/v1/audit?action=LOGIN_SUCCEEDED", admin), 200);
        assertThat(trail.path("items")).as("sign-ins are in the trail").isNotEmpty();

        JsonNode first = trail.path("items").get(0);
        assertThat(first.path("actorName").asText())
                .as("an audit trail whose sign-ins do not say who signed in is not one")
                .isNotBlank();
        assertThat(first.path("actorRole").asText()).isEqualTo("PLATFORM_ADMIN");

        // The same event is absent from the feed, on purpose: there it would
        // bury the approvals and invoices the Platform Admin is watching for.
        JsonNode feed = body(fetch("/api/v1/activity", admin), 200);
        assertThat(feed.path("items").toString())
                .as("the feed is a readable view, not the whole trail")
                .doesNotContain("LOGIN_SUCCEEDED");
    }

    @Test
    @DisplayName("is the Platform Admin's alone")
    void adminOnly() throws Exception {
        assertThat(fetch("/api/v1/audit", token(HEAD)).getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get("/api/v1/audit")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("filters by action and pages without skipping or repeating")
    void filtersAndPages() throws Exception {
        String admin = token(ADMIN);

        JsonNode all = body(fetch("/api/v1/audit", admin), 200);
        assertThat(all.path("total").asLong()).isPositive();
        assertThat(all.path("actions")).as("the actions present, for the filter").isNotEmpty();

        String anAction = all.path("items").get(0).path("action").asText();
        JsonNode filtered = body(fetch("/api/v1/audit?action=" + anAction, admin), 200);
        filtered.path("items").forEach(e ->
                assertThat(e.path("action").asText()).isEqualTo(anAction));

        // Keyset paging: everything after the cursor is older than the cursor.
        long firstId = all.path("items").get(0).path("id").asLong();
        JsonNode older = body(fetch("/api/v1/audit?before=" + firstId, admin), 200);
        older.path("items").forEach(e ->
                assertThat(e.path("id").asLong()).isLessThan(firstId));
    }

    // ---------------------------------------------------------------- helpers

    private MvcResult fetch(String path, String token) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
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
