package com.seastella.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * TSA-03: guided checks and problem types are content the Platform Admin
 * maintains in the app, and a Captain raising a request gets what was
 * published - with no code change and no restart (SoW s13).
 *
 * <p>One story, in order: the admin adds a problem type, drafts checks for it,
 * cannot publish them while they are broken, publishes, then revises them while
 * a Captain is part-way through the first version.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-flow-authoring-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("authoring guided checks")
class FlowAuthoringIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final String CAPTAIN = "master.kestrel@acme-shipmanagement.example";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private String admin;
    private String captain;
    private long categoryId;
    private long spareId;
    private long problemTypeId;
    private long flowId;
    private long firstVersionRequest;

    @BeforeAll
    void setUp() throws Exception {
        admin = token("admin@seastella.example");
        captain = token(CAPTAIN);
        // Equipment on the Captain's own vessel that has no seeded problem types or checks.
        Map<String, Object> row = jdbc.queryForMap("""
                select s.id as spare_id, c.id as category_id
                from spare s
                join equipment_category c on c.id = s.equipment_category_id
                join user_vessel_assignment a on a.vessel_id = s.vessel_id
                join app_user u on u.id = a.user_id
                where u.email = ? and c.code = 'NAVTEX'
                order by s.id limit 1
                """, CAPTAIN);
        spareId = ((Number) row.get("spare_id")).longValue();
        categoryId = ((Number) row.get("category_id")).longValue();
    }

    @Test
    @Order(1)
    @DisplayName("only the Platform Admin reaches authoring")
    void platformAdminOnly() throws Exception {
        assertThat(status(get("/api/v1/troubleshooting/flows"), captain)).isEqualTo(403);
        assertThat(status(get("/api/v1/problem-types"), captain)).isEqualTo(403);
        assertThat(status(get("/api/v1/troubleshooting/flows"), token("tech.head@acme-shipmanagement.example"))).isEqualTo(403);
        assertThat(status(get("/api/v1/troubleshooting/flows"), admin)).isEqualTo(200);
    }

    @Test
    @Order(2)
    @DisplayName("a new problem type is offered to Captains at once")
    void addProblemType() throws Exception {
        JsonNode created = body(send(post("/api/v1/problem-types"), admin,
                Map.of("equipmentCategoryId", categoryId, "label", "No messages received")), 201);
        problemTypeId = created.path("id").asLong();
        assertThat(created.path("code").asText()).isEqualTo("NAVTEX_NO_MESSAGES_RECEIVED");

        // Same label again, any case: refused.
        assertThat(send(post("/api/v1/problem-types"), admin,
                Map.of("equipmentCategoryId", categoryId, "label", "no messages RECEIVED")).getResponse().getStatus())
                .isEqualTo(409);

        long request = raise("NAVTEX silent since departure");
        JsonNode view = body(send(get("/api/v1/service-requests/" + request + "/troubleshooting"), captain, null), 200);
        assertThat(view.path("problemTypes").findValuesAsText("label")).contains("No messages received");
    }

    @Test
    @Order(3)
    @DisplayName("a draft is not what Captains get")
    void draftIsInvisible() throws Exception {
        JsonNode draft = body(send(post("/api/v1/troubleshooting/flows"), admin, Map.of(
                "name", "NAVTEX: no messages", "equipmentCategoryId", categoryId, "problemTypeId", problemTypeId,
                "firstQuestion", "Is the receiver switched on and showing its standby screen?")), 201);
        flowId = draft.path("id").asLong();
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("problems")).isEmpty();

        JsonNode started = startChecks(raise("NAVTEX not printing"), problemTypeId);
        assertThat(started.path("session").path("flowName").asText()).isEqualTo("General equipment checks");
    }

    @Test
    @Order(4)
    @DisplayName("a save quoting an old version is refused, not merged")
    void staleSaveRefused() throws Exception {
        JsonNode draft = flow(flowId);
        long version = draft.path("version").asLong();
        assertThat(save(draft, version, twoChecks("Is the receiver switched on?")).getResponse().getStatus()).isEqualTo(200);
        assertThat(save(draft, version, twoChecks("A late edit from another tab")).getResponse().getStatus()).isEqualTo(409);
        assertThat(flow(flowId).path("steps").get(0).path("prompt").asText()).isEqualTo("Is the receiver switched on?");
    }

    @Test
    @Order(5)
    @DisplayName("broken checks save as a draft but cannot be published")
    void brokenCannotPublish() throws Exception {
        JsonNode draft = flow(flowId);
        List<Map<String, Object>> steps = twoChecks("Is the receiver switched on?");
        steps.add(check("orphan", "Nothing leads here?", end("RESOLVED"), end("UNRESOLVED")));
        JsonNode saved = body(save(draft, draft.path("version").asLong(), steps), 200);
        assertThat(saved.path("problems")).hasSize(1);
        assertThat(saved.path("problems").get(0).asText()).isEqualTo("Check 3 is never reached: no answer leads to it.");

        MvcResult refused = publish(flowId, saved.path("version").asLong());
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("never reached");

        // An answer pointing at a check that does not exist is not even a valid draft.
        List<Map<String, Object>> dangling = twoChecks("Is the receiver switched on?");
        dangling.set(1, check("s2", "Are messages printing now?", next("nowhere"), end("UNRESOLVED")));
        assertThat(save(saved, saved.path("version").asLong(), dangling).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @Order(6)
    @DisplayName("published checks reach the next Captain who reports that problem")
    void publishReachesCaptain() throws Exception {
        JsonNode draft = flow(flowId);
        JsonNode saved = body(save(draft, draft.path("version").asLong(), twoChecks("Is the receiver switched on?")), 200);
        JsonNode published = body(publish(flowId, saved.path("version").asLong()), 200);
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.path("publishedBy").asText()).isNotBlank();

        firstVersionRequest = raise("NAVTEX no messages for 12 hours");
        JsonNode started = startChecks(firstVersionRequest, problemTypeId);
        assertThat(started.path("session").path("flowName").asText()).isEqualTo("NAVTEX: no messages");
        assertThat(started.path("session").path("flowVersion").asInt()).isEqualTo(1);
        assertThat(started.path("session").path("sampleContent").asBoolean()).isFalse();
        assertThat(started.path("session").path("currentStep").path("prompt").asText()).isEqualTo("Is the receiver switched on?");
    }

    @Test
    @Order(7)
    @DisplayName("revising published checks leaves a Captain mid-way on the version they started")
    void revisionDoesNotDisturbRunningChecks() throws Exception {
        JsonNode v2 = body(send(post("/api/v1/troubleshooting/flows/" + flowId + "/drafts"), admin, null), 200);
        long v2Id = v2.path("id").asLong();
        assertThat(v2Id).isNotEqualTo(flowId);
        assertThat(v2.path("flowVersion").asInt()).isEqualTo(2);
        // Asking again opens the same draft rather than a second one.
        assertThat(body(send(post("/api/v1/troubleshooting/flows/" + flowId + "/drafts"), admin, null), 200)
                .path("id").asLong()).isEqualTo(v2Id);

        List<Map<String, Object>> revised = twoChecks("Is the NAVTEX receiver powered, with its display lit?");
        JsonNode saved = body(save(v2, v2.path("version").asLong(), revised), 200);
        body(publish(v2Id, saved.path("version").asLong()), 200);
        assertThat(flow(flowId).path("status").asText()).isEqualTo("RETIRED");

        // The Captain who started on version 1 answers and continues on version 1.
        JsonNode running = body(send(get("/api/v1/service-requests/" + firstVersionRequest + "/troubleshooting"), captain, null), 200);
        long stepId = running.path("session").path("currentStep").path("id").asLong();
        JsonNode answered = body(send(post("/api/v1/service-requests/" + firstVersionRequest + "/troubleshooting/answers"),
                captain, Map.of("stepId", stepId, "yes", true)), 200);
        assertThat(answered.path("session").path("flowVersion").asInt()).isEqualTo(1);
        assertThat(answered.path("session").path("currentStep").path("prompt").asText()).isEqualTo("Are messages printing now?");

        // A new request gets version 2.
        JsonNode fresh = startChecks(raise("NAVTEX display dark"), problemTypeId);
        assertThat(fresh.path("session").path("flowVersion").asInt()).isEqualTo(2);
        assertThat(fresh.path("session").path("currentStep").path("prompt").asText())
                .isEqualTo("Is the NAVTEX receiver powered, with its display lit?");
        flowId = v2Id;
    }

    @Test
    @Order(8)
    @DisplayName("one set of checks per problem: a second is refused")
    void oneFlowPerTarget() throws Exception {
        MvcResult second = send(post("/api/v1/troubleshooting/flows"), admin, Map.of(
                "name", "Another NAVTEX set", "equipmentCategoryId", categoryId, "problemTypeId", problemTypeId,
                "firstQuestion", "Anything?"));
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(second.getResponse().getContentAsString()).contains("already covers");
    }

    @Test
    @Order(9)
    @DisplayName("withdrawn checks fall back to the general checks")
    void retireFallsBack() throws Exception {
        assertThat(body(send(post("/api/v1/troubleshooting/flows/" + flowId + "/retirement"), admin, null), 200)
                .path("status").asText()).isEqualTo("RETIRED");
        JsonNode started = startChecks(raise("NAVTEX paper jam alarm"), problemTypeId);
        assertThat(started.path("session").path("flowName").asText()).isEqualTo("General equipment checks");
    }

    @Test
    @Order(10)
    @DisplayName("a draft can be discarded; published history cannot")
    void discard() throws Exception {
        JsonNode v3 = body(send(post("/api/v1/troubleshooting/flows/" + flowId + "/drafts"), admin, null), 200);
        long v3Id = v3.path("id").asLong();
        assertThat(v3.path("flowVersion").asInt()).isEqualTo(3);

        assertThat(send(delete("/api/v1/troubleshooting/flows/" + flowId), admin, null).getResponse().getStatus()).isEqualTo(409);
        assertThat(send(delete("/api/v1/troubleshooting/flows/" + v3Id), admin, null).getResponse().getStatus()).isEqualTo(204);
        assertThat(send(get("/api/v1/troubleshooting/flows/" + v3Id), admin, null).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @Order(11)
    @DisplayName("a retired problem type is no longer offered, and cannot be chosen")
    void retireProblemType() throws Exception {
        JsonNode retired = body(send(put("/api/v1/problem-types/" + problemTypeId), admin, Map.of("active", false)), 200);
        assertThat(retired.path("active").asBoolean()).isFalse();
        assertThat(retired.path("requestCount").asLong()).isPositive();

        long request = raise("NAVTEX intermittent");
        JsonNode view = body(send(get("/api/v1/service-requests/" + request + "/troubleshooting"), captain, null), 200);
        assertThat(view.path("problemTypes").findValuesAsText("label")).doesNotContain("No messages received");
        assertThat(send(post("/api/v1/service-requests/" + request + "/troubleshooting"), captain,
                Map.of("problemTypeId", problemTypeId)).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @Order(12)
    @DisplayName("every change to content is audited")
    void audited() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String action : List.of("PROBLEM_TYPE_CREATED", "PROBLEM_TYPE_UPDATED", "CHECKS_DRAFT_SAVED",
                "CHECKS_PUBLISHED", "CHECKS_RETIRED", "CHECKS_DRAFT_DISCARDED")) {
            counts.put(action, jdbc.queryForObject("select count(*) from audit_entry where action = ?", Integer.class, action));
        }
        assertThat(counts).allSatisfy((action, n) -> assertThat(n).as(action).isPositive());
        assertThat(counts.get("CHECKS_PUBLISHED")).isEqualTo(2);
    }

    // ---------------------------------------------------------------- helpers

    private static List<Map<String, Object>> twoChecks(String firstPrompt) {
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(check("s1", firstPrompt, next("s2"), end("UNRESOLVED")));
        steps.add(check("s2", "Are messages printing now?", end("RESOLVED"), end("TEMPORARY_FIX")));
        return steps;
    }

    private static Map<String, Object> check(String key, String prompt, Map<String, Object> yes, Map<String, Object> no) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("prompt", prompt);
        m.put("yes", yes);
        m.put("no", no);
        return m;
    }

    private static Map<String, Object> next(String key) { return Map.of("nextKey", key); }

    private static Map<String, Object> end(String outcome) { return Map.of("outcome", outcome); }

    private MvcResult save(JsonNode draft, long version, List<Map<String, Object>> steps) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("name", draft.path("name").asText());
        body.put("equipmentCategoryId", draft.path("target").path("equipmentCategoryId").asLong());
        body.put("problemTypeId", draft.path("target").path("problemTypeId").asLong());
        body.put("sampleContent", false);
        body.put("startStepKey", "s1");
        body.put("steps", steps);
        return send(put("/api/v1/troubleshooting/flows/" + draft.path("id").asLong()), admin, body);
    }

    private MvcResult publish(long id, long version) throws Exception {
        return send(post("/api/v1/troubleshooting/flows/" + id + "/publication"), admin, Map.of("version", version));
    }

    private JsonNode flow(long id) throws Exception {
        return body(send(get("/api/v1/troubleshooting/flows/" + id), admin, null), 200);
    }

    private long raise(String title) throws Exception {
        return body(send(post("/api/v1/service-requests"), captain, Map.of("spareId", spareId, "title", title,
                "description", "Raised by the authoring test.", "priority", "MEDIUM")), 201)
                .path("request").path("id").asLong();
    }

    private JsonNode startChecks(long requestId, long problemType) throws Exception {
        return body(send(post("/api/v1/service-requests/" + requestId + "/troubleshooting"), captain,
                Map.of("problemTypeId", problemType)), 200);
    }

    private int status(MockHttpServletRequestBuilder request, String token) throws Exception {
        return send(request, token, null).getResponse().getStatus();
    }

    private MvcResult send(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(request).andReturn();
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
