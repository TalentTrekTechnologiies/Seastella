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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Deleting a vessel takes everything on it - equipment, stock, history,
 * requests with their checks, chats and invoices, documents, notifications,
 * assignments - and leaves other vessels and the audit trail alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-vessel-removal-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("deleting a vessel")
class VesselRemovalIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final String CAPTAIN = "master.kestrel@acme-shipmanagement.example";
    private static final String HEAD = "tech.head@acme-shipmanagement.example";

    /** Every table that holds rows for a vessel. None may keep any once it is deleted. */
    private static final List<String> BY_VESSEL = List.of(
            "spare", "replacement_part", "spare_service_record", "running_hour_reading", "document",
            "spare_due_state", "spare_maintenance_rule", "service_request", "service_request_transition",
            "completion_report", "invoice", "troubleshooting_session", "conversation", "notification",
            "import_row", "user_vessel_assignment");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private String head;
    private String captain;

    @BeforeAll
    void setUp() throws Exception {
        head = token(HEAD);
        captain = token(CAPTAIN);
    }

    @Test
    @DisplayName("everything on the vessel goes, and nothing else")
    void deletesTheVesselAndEverythingOnIt() throws Exception {
        // The seeded vessel with the most going on: requests, invoices, equipment.
        long vesselId = jdbc.queryForObject("""
                select v.id from vessel v
                join organization o on o.id = v.organization_id
                where o.name like 'Acme%'
                order by (select count(*) from service_request r where r.vessel_id = v.id) desc,
                         (select count(*) from invoice i where i.vessel_id = v.id) desc
                limit 1
                """, Long.class);
        String name = jdbc.queryForObject("select name from vessel where id = ?", String.class, vesselId);
        long otherVessel = jdbc.queryForObject(
                "select id from vessel where id <> ? order by id limit 1", Long.class, vesselId);
        int otherSpares = rows("spare", otherVessel);
        int requests = rows("service_request", vesselId);
        assertThat(requests).as("the chosen vessel has service requests to delete").isPositive();
        assertThat(rows("spare", vesselId)).isPositive();

        // Only the office deletes a vessel.
        assertThat(mvc.perform(get("/api/v1/vessels/" + vesselId + "/removal")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + captain)).andReturn().getResponse().getStatus())
                .isEqualTo(403);

        // What would go is stated before anything goes.
        JsonNode impact = body(mvc.perform(get("/api/v1/vessels/" + vesselId + "/removal")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn(), 200);
        assertThat(impact.path("serviceRequests").asInt()).isEqualTo(requests);
        assertThat(impact.path("equipment").asInt()).isEqualTo(rows("spare", vesselId));
        assertThat(impact.path("invoices").asInt()).isEqualTo(rows("invoice", vesselId));

        // The wrong name deletes nothing.
        MvcResult wrong = mvc.perform(delete("/api/v1/vessels/" + vesselId).param("confirm", "not the name")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn();
        assertThat(wrong.getResponse().getStatus()).isEqualTo(400);
        assertThat(rows("service_request", vesselId)).isEqualTo(requests);

        // The right name deletes it all.
        body(mvc.perform(delete("/api/v1/vessels/" + vesselId).param("confirm", name)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn(), 200);

        assertThat(jdbc.queryForObject("select count(*) from vessel where id = ?", Integer.class, vesselId)).isZero();
        for (String table : BY_VESSEL) {
            assertThat(rows(table, vesselId)).as(table + " rows left for the deleted vessel").isZero();
        }
        // Children that hold no vessel id of their own went with their parents.
        assertThat(jdbc.queryForObject("""
                select count(*) from conversation_message m
                where not exists (select 1 from conversation c where c.id = m.conversation_id)
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from troubleshooting_response t
                where not exists (select 1 from troubleshooting_session s where s.id = t.session_id)
                """, Integer.class)).isZero();

        // Other vessels are untouched, and the audit trail records the deletion.
        assertThat(rows("spare", otherVessel)).isEqualTo(otherSpares);
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_entry where action = 'VESSEL_DELETED' and entity_id = ?",
                Integer.class, vesselId)).isEqualTo(1);

        // The fleet list no longer shows it.
        JsonNode fleet = body(mvc.perform(get("/api/v1/vessels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + head)).andReturn(), 200);
        fleet.forEach(v -> assertThat(v.path("id").asLong()).isNotEqualTo(vesselId));
    }

    private int rows(String table, long vesselId) {
        // Table names come only from the fixed list above.
        return switch (table) {
            case "spare" -> count("select count(*) from spare where vessel_id = ?", vesselId);
            case "replacement_part" -> count("select count(*) from replacement_part where vessel_id = ?", vesselId);
            case "spare_service_record" -> count("select count(*) from spare_service_record where vessel_id = ?", vesselId);
            case "running_hour_reading" -> count("select count(*) from running_hour_reading where vessel_id = ?", vesselId);
            case "document" -> count("select count(*) from document where vessel_id = ?", vesselId);
            case "spare_due_state" -> count("select count(*) from spare_due_state where vessel_id = ?", vesselId);
            case "spare_maintenance_rule" -> count("select count(*) from spare_maintenance_rule where vessel_id = ?", vesselId);
            case "service_request" -> count("select count(*) from service_request where vessel_id = ?", vesselId);
            case "service_request_transition" -> count("select count(*) from service_request_transition where vessel_id = ?", vesselId);
            case "completion_report" -> count("select count(*) from completion_report where vessel_id = ?", vesselId);
            case "invoice" -> count("select count(*) from invoice where vessel_id = ?", vesselId);
            case "troubleshooting_session" -> count("select count(*) from troubleshooting_session where vessel_id = ?", vesselId);
            case "conversation" -> count("select count(*) from conversation where vessel_id = ?", vesselId);
            case "notification" -> count("select count(*) from notification where vessel_id = ?", vesselId);
            case "import_row" -> count("select count(*) from import_row where vessel_id = ?", vesselId);
            case "user_vessel_assignment" -> count("select count(*) from user_vessel_assignment where vessel_id = ?", vesselId);
            default -> throw new IllegalArgumentException(table);
        };
    }

    private int count(String sql, long vesselId) {
        Integer n = jdbc.queryForObject(sql, Integer.class, vesselId);
        return n == null ? 0 : n;
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
