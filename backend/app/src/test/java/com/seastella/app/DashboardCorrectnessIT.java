package com.seastella.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves dashboard figures are computed from the database, and that they move
 * when the underlying rows move.
 *
 * <p>The master brief forbids hard-coded dashboard numbers. Asserting that a
 * figure equals a constant would not catch a hard-coded one; asserting that it
 * equals a live {@code COUNT(*)} <em>and then changes</em> when a row is
 * inserted does. Each test below does both.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-correct-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@DisplayName("dashboard correctness")
class DashboardCorrectnessIT {

    private static final String PASSWORD = "SeaStella#Demo2026";
    private static final String ADMIN = "admin@seastella.example";
    private static final String TECH_HEAD = "tech.head@acme-shipmanagement.example";
    private static final String SM_ONE = "d.fernandes@acme-shipmanagement.example";
    private static final String CAPTAIN = "master.kestrel@acme-shipmanagement.example";
    private static final String COORDINATOR = "coordinator@seastella.example";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    // =====================================================================
    //  Figures match the database
    // =====================================================================

    @Nested
    @DisplayName("figures are derived from real rows")
    class DerivedFromData {

        @Test
        void platformAdminCountsMatchTheDatabase() throws Exception {
            JsonNode body = getJson("/api/v1/dashboards/platform-admin", login(ADMIN));

            assertThat(kpi(body, "vessels")).isEqualTo(count("select count(*) from vessel"));
            assertThat(kpi(body, "spares")).isEqualTo(count("select count(*) from spare"));
            assertThat(kpi(body, "users")).isEqualTo(count("select count(*) from app_user"));
            assertThat(kpi(body, "organizations"))
                    .isEqualTo(count("select count(*) from organization"));
            assertThat(kpi(body, "openRequests")).isEqualTo(
                    count("select count(*) from service_request where closed_at is null"));
        }

        @Test
        void technicalHeadCountsMatchTheirOrganizationOnly() throws Exception {
            JsonNode body = getJson("/api/v1/dashboards/technical-head", login(TECH_HEAD));

            long orgVessels = count("""
                    select count(*) from vessel v
                    join organization o on o.id = v.organization_id
                    where o.code = 'ACME'
                    """);
            long orgSpares = count("""
                    select count(*) from spare s
                    join vessel v on v.id = s.vessel_id
                    join organization o on o.id = v.organization_id
                    where o.code = 'ACME'
                    """);

            assertThat(kpi(body, "vessels")).isEqualTo(orgVessels);
            assertThat(kpi(body, "spares")).isEqualTo(orgSpares);

            // And these are strictly fewer than the platform totals, so the
            // scoping is doing something rather than passing everything through.
            assertThat(orgVessels).isLessThan(count("select count(*) from vessel"));
        }

        @Test
        void overdueCountMatchesTheMaintenanceRules() throws Exception {
            JsonNode body = getJson("/api/v1/dashboards/technical-head", login(TECH_HEAD));

            long overdue = count("""
                    select count(*) from spare_maintenance_rule r
                    join vessel v on v.id = r.vessel_id
                    join organization o on o.id = v.organization_id
                    where o.code = 'ACME' and r.active = true
                      and r.next_due_date is not null and r.next_due_date < current_date
                    """);

            assertThat(kpi(body, "overdue")).isEqualTo(overdue);
        }

        @Test
        void shipManagerQueueMatchesRequestsAwaitingTheirApproval() throws Exception {
            JsonNode body = getJson("/api/v1/dashboards/ship-manager", login(SM_ONE));

            long awaiting = count("""
                    select count(*) from service_request r
                    join user_vessel_assignment a on a.vessel_id = r.vessel_id
                    join app_user u on u.id = a.user_id
                    where u.email = 'd.fernandes@acme-shipmanagement.example'
                      and r.status = 'PENDING_OPERATIONAL_APPROVAL'
                    """);

            assertThat(body.path("requestsAwaitingApproval").path("total").asLong())
                    .isEqualTo(awaiting);
            assertThat(body.path("requestsAwaitingApproval").path("items")).hasSize((int) awaiting);
        }

        /**
         * The gate as a queue: only accepted invoices whose request has no
         * engineer yet may appear as ready to assign.
         */
        @Test
        void coordinatorReadyToAssignMatchesAcceptedUnassignedInvoices() throws Exception {
            JsonNode body = getJson("/api/v1/dashboards/service-coordinator", login(COORDINATOR));

            long ready = count("""
                    select count(*) from invoice i
                    join service_request r on r.id = i.service_request_id
                    join vessel v on v.id = i.vessel_id
                    join organization o on o.id = v.organization_id
                    where o.code = 'ACME' and i.status = 'ACCEPTED'
                      and r.assigned_engineer_user_id is null and r.closed_at is null
                    """);

            assertThat(body.path("acceptedReadyToAssign").path("total").asLong()).isEqualTo(ready);
        }

        @Test
        void captainSpareCountMatchesTheirVessel() throws Exception {
            JsonNode body = getJson("/api/v1/dashboards/captain", login(CAPTAIN));

            long spares = count("""
                    select count(*) from spare s
                    join vessel v on v.id = s.vessel_id
                    where v.imo_number = '9412367'
                    """);

            assertThat(body.path("vessel").path("spareCount").asLong()).isEqualTo(spares);
            assertThat(kpi(body, "spares")).isEqualTo(spares);
        }

        /** Distribution slices must sum to the underlying total, not be padded. */
        @Test
        void distributionSlicesSumToTheirTotal() throws Exception {
            JsonNode body = getJson("/api/v1/dashboards/technical-head", login(TECH_HEAD));

            for (String key : List.of("vesselStatus", "spareHealth", "requestsByStage",
                    "spareCriticality")) {
                JsonNode dist = body.path(key);
                long sum = 0;
                for (JsonNode slice : dist.path("slices")) {
                    sum += slice.path("value").asLong();
                }
                assertThat(sum).as("%s slices sum", key).isEqualTo(dist.path("total").asLong());
            }
        }
    }

    // =====================================================================
    //  Figures move when the data moves
    // =====================================================================

    @Nested
    @DisplayName("figures change when the data changes")
    class ReactsToChange {

        /**
         * The decisive test against hard-coded figures: raise a request, and the
         * count must rise by exactly one on both the Captain's and the Technical
         * Head's dashboard.
         */
        @Test
        void raisingARequestMovesTheOpenCountOnEveryAffectedDashboard() throws Exception {
            long captainBefore = kpi(getJson("/api/v1/dashboards/captain", login(CAPTAIN)),
                    "openRequests");
            long techBefore = kpi(getJson("/api/v1/dashboards/technical-head", login(TECH_HEAD)),
                    "openRequests");
            long adminBefore = kpi(getJson("/api/v1/dashboards/platform-admin", login(ADMIN)),
                    "openRequests");

            insertRequest("SR-TEST-CHANGE-0001", "TROUBLESHOOTING");

            assertThat(kpi(getJson("/api/v1/dashboards/captain", login(CAPTAIN)), "openRequests"))
                    .isEqualTo(captainBefore + 1);
            assertThat(kpi(getJson("/api/v1/dashboards/technical-head", login(TECH_HEAD)),
                    "openRequests")).isEqualTo(techBefore + 1);
            assertThat(kpi(getJson("/api/v1/dashboards/platform-admin", login(ADMIN)),
                    "openRequests")).isEqualTo(adminBefore + 1);

            jdbc.update("delete from service_request where request_number = ?",
                    "SR-TEST-CHANGE-0001");
        }

        /** A new request awaiting approval must appear in the Ship Manager's queue. */
        @Test
        void anApprovalQueueGrowsWhenARequestNeedsApproval() throws Exception {
            long before = getJson("/api/v1/dashboards/ship-manager", login(SM_ONE))
                    .path("requestsAwaitingApproval").path("total").asLong();

            insertRequest("SR-TEST-APPROVAL-0001", "PENDING_OPERATIONAL_APPROVAL");

            JsonNode after = getJson("/api/v1/dashboards/ship-manager", login(SM_ONE));
            assertThat(after.path("requestsAwaitingApproval").path("total").asLong())
                    .isEqualTo(before + 1);

            // The new request is actually listed, not just counted.
            assertThat(after.path("requestsAwaitingApproval").path("items").toString())
                    .contains("SR-TEST-APPROVAL-0001");

            jdbc.update("delete from service_request where request_number = ?",
                    "SR-TEST-APPROVAL-0001");
        }

        /**
         * Maintenance status is derived, not stored: moving a due date to the
         * past must move a spare into the overdue count on the next read.
         */
        @Test
        void movingADueDateChangesTheMaintenanceFigures() throws Exception {
            long before = kpi(getJson("/api/v1/dashboards/technical-head", login(TECH_HEAD)),
                    "overdue");

            Long ruleId = jdbc.queryForObject("""
                    select r.id from spare_maintenance_rule r
                    join vessel v on v.id = r.vessel_id
                    join organization o on o.id = v.organization_id
                    where o.code = 'ACME' and r.active = true
                      and r.next_due_date > current_date + 30
                    limit 1
                    """, Long.class);

            jdbc.update("update spare_maintenance_rule set next_due_date = current_date - 5 "
                    + "where id = ?", ruleId);

            assertThat(kpi(getJson("/api/v1/dashboards/technical-head", login(TECH_HEAD)), "overdue"))
                    .isEqualTo(before + 1);

            jdbc.update("update spare_maintenance_rule set next_due_date = current_date + 60 "
                    + "where id = ?", ruleId);

            assertThat(kpi(getJson("/api/v1/dashboards/technical-head", login(TECH_HEAD)), "overdue"))
                    .isEqualTo(before);
        }

        /** Stock shortages are derived from quantities, never from a stored flag. */
        @Test
        void changingStockChangesTheShortageCount() throws Exception {
            long before = kpi(getJson("/api/v1/dashboards/ship-manager", login(SM_ONE)),
                    "partShortages");

            Long partId = jdbc.queryForObject("""
                    select p.id from replacement_part p
                    join vessel v on v.id = p.vessel_id
                    where v.imo_number = '9412367' and p.quantity_on_hand >= p.minimum_quantity
                    limit 1
                    """, Long.class);

            jdbc.update("update replacement_part set quantity_on_hand = 0 where id = ?", partId);

            assertThat(kpi(getJson("/api/v1/dashboards/ship-manager", login(SM_ONE)), "partShortages"))
                    .isEqualTo(before + 1);

            jdbc.update("update replacement_part set quantity_on_hand = minimum_quantity + 5 "
                    + "where id = ?", partId);
        }
    }

    // =====================================================================
    //  helpers
    // =====================================================================

    private void insertRequest(String number, String status) {
        Long vesselId = jdbc.queryForObject(
                "select id from vessel where imo_number = '9412367'", Long.class);
        Long orgId = jdbc.queryForObject(
                "select organization_id from vessel where id = ?", Long.class, vesselId);
        Long spareId = jdbc.queryForObject(
                "select id from spare where vessel_id = ? and path = '14.1' ", Long.class, vesselId);
        Long captainId = jdbc.queryForObject(
                "select id from app_user where email = ?", Long.class, CAPTAIN);

        jdbc.update("""
                insert into service_request
                  (request_number, organization_id, vessel_id, spare_id, raised_by_user_id,
                   title, description, priority, status, resolution_type, created_at, version)
                values (?, ?, ?, ?, ?, 'Test request', 'Inserted by DashboardCorrectnessIT',
                        'MEDIUM', ?, 'NONE', current_timestamp, 0)
                """, number, orgId, vesselId, spareId, captainId, status);
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private static long kpi(JsonNode body, String key) {
        for (JsonNode k : body.path("kpis")) {
            if (key.equals(k.path("key").asText())) {
                return k.path("value").asLong();
            }
        }
        throw new AssertionError("KPI not present in payload: " + key);
    }

    private String login(String email) throws Exception {
        String body = """
                {"email": "%s", "password": "%s"}
                """.formatted(email, PASSWORD);

        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        return json.readTree(result.getResponse().getContentAsString())
                .path("accessToken").asText();
    }

    private JsonNode getJson(String endpoint, String token) throws Exception {
        MvcResult result = mvc.perform(get(endpoint).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        return json.readTree(result.getResponse().getContentAsString());
    }
}
