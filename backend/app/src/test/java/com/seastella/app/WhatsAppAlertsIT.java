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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * WhatsApp alerts, built ahead of the client's Meta account: each person
 * gives their own number with consent, the Platform Admin chooses which
 * alerts use it, and until the account details arrive every WhatsApp alert is
 * queued and recorded as skipped - never silently dropped, never sent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-whatsapp-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("WhatsApp alerts")
class WhatsAppAlertsIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final String ADMIN = "admin@seastella.example";
    private static final String CAPTAIN = "master.kestrel@acme-shipmanagement.example";
    private static final String SHIP_MANAGER = "d.fernandes@acme-shipmanagement.example";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private String admin;
    private String captain;
    private String shipManager;

    @BeforeAll
    void setUp() throws Exception {
        admin = token(ADMIN);
        captain = token(CAPTAIN);
        shipManager = token(SHIP_MANAGER);
    }

    @Test
    @DisplayName("a person sets their own number, only with consent, and can remove it")
    void ownNumber() throws Exception {
        assertThat(call(put("/api/v1/auth/whatsapp"), shipManager,
                Map.of("number", "98765 43210", "consent", false)).getResponse().getStatus()).isEqualTo(400);
        assertThat(call(put("/api/v1/auth/whatsapp"), shipManager,
                Map.of("number", "12345", "consent", true)).getResponse().getStatus()).isEqualTo(400);

        JsonNode saved = body(call(put("/api/v1/auth/whatsapp"), shipManager,
                Map.of("number", "98765 43210", "consent", true)), 200);
        assertThat(saved.path("number").asText()).isEqualTo("+919876543210");
        assertThat(saved.path("optedIn").asBoolean()).isTrue();
        assertThat(body(call(get("/api/v1/auth/whatsapp"), shipManager, null), 200)
                .path("number").asText()).isEqualTo("+919876543210");

        JsonNode removed = body(call(put("/api/v1/auth/whatsapp"), captain,
                Map.of("number", "", "consent", false)), 200);
        assertThat(removed.path("optedIn").asBoolean()).isFalse();
        assertThat(removed.path("number").isTextual()).isFalse();
    }

    @Test
    @DisplayName("a request raised queues a WhatsApp alert for the opted-in Ship Manager, beside the email")
    void alertIsQueued() throws Exception {
        body(call(put("/api/v1/auth/whatsapp"), shipManager, Map.of("number", "+91 98765 43210", "consent", true)), 200);

        long requestId = raise();

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.channel, d.address, d.status, d.last_error from notification_delivery d
                join notification n on n.id = d.notification_id
                join app_user u on u.id = n.recipient_user_id
                where n.entity_id = ? and n.event_type = 'RAISE' and u.email = ?
                """, requestId, SHIP_MANAGER);
        assertThat(rows).extracting(r -> r.get("channel")).contains("EMAIL", "WHATSAPP");
        Map<String, Object> whatsapp = rows.stream().filter(r -> "WHATSAPP".equals(r.get("channel"))).findFirst().orElseThrow();
        assertThat(whatsapp.get("address")).isEqualTo("+919876543210");
        // No Meta account in tests: queued, then recorded as skipped with the reason.
        assertThat((String) whatsapp.get("status")).isIn("PENDING", "SKIPPED");
    }

    @Test
    @DisplayName("while paused, a new WhatsApp alert is recorded as skipped and email carries on")
    void pause() throws Exception {
        body(call(put("/api/v1/auth/whatsapp"), shipManager, Map.of("number", "+919876543210", "consent", true)), 200);
        assertThat(body(call(put("/api/v1/notifications/whatsapp"), admin, Map.of("enabled", false)), 200)
                .path("enabled").asBoolean()).isFalse();
        try {
            long requestId = raise();
            Map<String, Object> row = jdbc.queryForMap("""
                    select d.status, d.last_error from notification_delivery d
                    join notification n on n.id = d.notification_id
                    where n.entity_id = ? and n.event_type = 'RAISE' and d.channel = 'WHATSAPP'
                    """, requestId);
            assertThat(row.get("status")).isEqualTo("SKIPPED");
            assertThat((String) row.get("last_error")).contains("paused");
        } finally {
            body(call(put("/api/v1/notifications/whatsapp"), admin, Map.of("enabled", true)), 200);
        }
    }

    @Test
    @DisplayName("the Platform Admin sees the setup, names templates per alert, and tests safely")
    void adminControls() throws Exception {
        JsonNode settings = body(call(get("/api/v1/notifications/whatsapp"), admin, null), 200);
        assertThat(settings.path("configured").asBoolean()).isFalse();
        assertThat(settings.path("defaultTemplate").asText()).isEqualTo("thawe_alert");

        JsonNode rules = body(call(get("/api/v1/notifications/rules"), admin, null), 200);
        JsonNode raise = null;
        for (JsonNode r : rules) {
            if ("RAISE".equals(r.path("eventType").asText()) && "SHIP_MANAGER".equals(r.path("recipientRole").asText())) {
                raise = r;
            }
        }
        assertThat(raise).isNotNull();
        assertThat(raise.path("whatsapp").asBoolean()).isTrue();

        JsonNode named = body(call(put("/api/v1/notifications/whatsapp/templates/RAISE_INVOICE"), admin,
                Map.of("templateName", "thawe_invoice", "languageCode", "en_US", "linkButton", false)), 200);
        JsonNode invoice = find(named, "RAISE_INVOICE");
        assertThat(invoice.path("templateName").asText()).isEqualTo("thawe_invoice");
        assertThat(invoice.path("usesDefault").asBoolean()).isFalse();
        assertThat(call(put("/api/v1/notifications/whatsapp/templates/RAISE_INVOICE"), admin,
                Map.of("templateName", "Invoice Template!")).getResponse().getStatus()).isEqualTo(400);
        JsonNode reset = body(call(put("/api/v1/notifications/whatsapp/templates/RAISE_INVOICE"), admin,
                Map.of("templateName", "")), 200);
        assertThat(find(reset, "RAISE_INVOICE").path("usesDefault").asBoolean()).isTrue();

        assertThat(body(call(post("/api/v1/notifications/whatsapp/test"), admin, Map.of("helloWorld", true)), 200)
                .path("status").asText()).isEqualTo("NOT_SET_UP");

        assertThat(call(get("/api/v1/notifications/whatsapp"), captain, null).getResponse().getStatus())
                .isEqualTo(403);
    }

    private long raise() throws Exception {
        long spareId = jdbc.queryForObject("""
                select s.id from spare s
                join user_vessel_assignment a on a.vessel_id = s.vessel_id
                join app_user u on u.id = a.user_id
                where u.email = ? and s.id not in (select spare_id from service_request)
                order by s.id limit 1
                """, Long.class, CAPTAIN);
        JsonNode raised = body(call(post("/api/v1/service-requests"), captain, Map.of(
                "spareId", spareId, "title", "Gyro repeater drifting",
                "description", "Heading off by two degrees.", "priority", "HIGH")), 201);
        return raised.path("request").path("id").asLong();
    }

    private static JsonNode find(JsonNode list, String eventType) {
        for (JsonNode t : list) {
            if (eventType.equals(t.path("eventType").asText())) return t;
        }
        throw new AssertionError(eventType + " not listed");
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token, Object payload) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (payload != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
        }
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
