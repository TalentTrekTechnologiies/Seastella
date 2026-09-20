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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Replacement parts on board (SoW §9.5, §11): counting stock, the minimum to
 * hold, and the alert that fires when a count takes a part below it (SPR-14),
 * with every change audited (AUD-06).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-parts-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("replacement part stock")
class PartStockIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final String CAPTAIN = "master.kestrel@acme-shipmanagement.example";
    private static final String HEAD = "tech.head@acme-shipmanagement.example";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private String captain;
    private String head;
    private long vesselId;

    @BeforeAll
    void setUp() throws Exception {
        captain = token(CAPTAIN);
        head = token(HEAD);
        vesselId = jdbc.queryForObject("""
                select a.vessel_id from user_vessel_assignment a
                join app_user u on u.id = a.user_id
                where u.email = ? limit 1
                """, Long.class, CAPTAIN);
    }

    @Test
    @DisplayName("the Captain counts stock, and a count below the minimum alerts the vessel and the office")
    void countingRaisesShortageAlert() throws Exception {
        long partId = wellStockedPart();
        int minimum = jdbc.queryForObject("select minimum_quantity from replacement_part where id = ?",
                Integer.class, partId);

        // A count that is still healthy: recorded, audited, and nobody alerted.
        JsonNode healthy = body(count(captain, partId, minimum + 2, "Monthly count"), 200);
        assertThat(healthy.path("belowMinimum").asBoolean()).isFalse();
        assertThat(alertCount(partId)).isZero();

        // A count that takes it below the minimum: the crossing is what alerts.
        JsonNode shortNow = body(count(captain, partId, Math.max(minimum - 1, 0), "Used two in the last service"), 200);
        assertThat(shortNow.path("belowMinimum").asBoolean()).isTrue();
        assertThat(alertCount(partId)).isPositive();

        List<String> roles = jdbc.queryForList("""
                select distinct u.role from notification n
                join app_user u on u.id = n.recipient_user_id
                where n.event_type = 'PART_SHORTAGE' and n.entity_id = ?
                """, String.class, partId);
        assertThat(roles).contains("CAPTAIN", "SHIP_MANAGER", "TECHNICAL_HEAD");

        String alertBody = jdbc.queryForObject("""
                select body from notification where event_type = 'PART_SHORTAGE' and entity_id = ? limit 1
                """, String.class, partId);
        assertThat(alertBody).contains("is down to", "minimum " + minimum, "Order a replacement");

        // Staying short does not alert again; only crossing does.
        int alertsAfterFirst = alertCount(partId);
        body(count(captain, partId, Math.max(minimum - 2, 0), null), 200);
        assertThat(alertCount(partId)).isEqualTo(alertsAfterFirst);

        assertThat(jdbc.queryForObject("""
                select count(*) from audit_entry where action = 'PART_STOCK_CHANGED' and entity_id = ?
                """, Integer.class, partId)).isEqualTo(3);
    }

    @Test
    @DisplayName("raising the minimum can put a part short, and that alerts too")
    void raisingTheMinimumAlerts() throws Exception {
        long partId = wellStockedPart();
        int onHand = jdbc.queryForObject("select quantity_on_hand from replacement_part where id = ?",
                Integer.class, partId);

        JsonNode configured = body(mvc.perform(put("/api/v1/parts/" + partId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("minimumQuantity", onHand + 5, "location", "Bridge store")))
        ).andReturn(), 200);
        assertThat(configured.path("belowMinimum").asBoolean()).isTrue();
        assertThat(configured.path("location").asText()).isEqualTo("Bridge store");
        assertThat(alertCount(partId)).isPositive();
    }

    @Test
    @DisplayName("the minimum is the office's to set, not the bridge's")
    void captainCannotSetTheMinimum() throws Exception {
        long partId = wellStockedPart();
        assertThat(mvc.perform(put("/api/v1/parts/" + partId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + captain)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("minimumQuantity", 99))))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("a part on another fleet's vessel is not there")
    void scoped() throws Exception {
        Long otherPart = jdbc.queryForObject("""
                select p.id from replacement_part p
                join vessel v on v.id = p.vessel_id
                where v.organization_id <> (select organization_id from app_user where email = ?)
                order by p.id limit 1
                """, Long.class, HEAD);
        assertThat(count(head, otherPart, 1, null).getResponse().getStatus()).isEqualTo(404);

        Long otherVessel = jdbc.queryForObject("select vessel_id from replacement_part where id = ?",
                Long.class, otherPart);
        assertThat(mvc.perform(get("/api/v1/vessels/" + otherVessel + "/parts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("a nonsense count is refused")
    void validation() throws Exception {
        long partId = wellStockedPart();
        assertThat(count(captain, partId, -1, null).getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(put("/api/v1/parts/" + partId + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + captain)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    // ---------------------------------------------------------------- helpers

    /** A part with room above its minimum, so a test can push it under. */
    private long wellStockedPart() {
        return jdbc.queryForObject("""
                select id from replacement_part
                where vessel_id = ? and quantity_on_hand >= minimum_quantity
                order by (quantity_on_hand - minimum_quantity) desc, id
                limit 1
                """, Long.class, vesselId);
    }

    private int alertCount(long partId) {
        return jdbc.queryForObject(
                "select count(*) from notification where event_type = 'PART_SHORTAGE' and entity_id = ?",
                Integer.class, partId);
    }

    private MvcResult count(String token, Long partId, int quantity, String note) throws Exception {
        Map<String, Object> payload = note == null
                ? Map.of("quantityOnHand", quantity)
                : Map.of("quantityOnHand", quantity, "note", note);
        return mvc.perform(put("/api/v1/parts/" + partId + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
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
