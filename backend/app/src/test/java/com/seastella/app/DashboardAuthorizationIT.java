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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization and scoping across all six dashboards, exercised over real HTTP
 * with real tokens against the seeded dataset.
 *
 * <p>Every assertion here is a denial or a containment check. Proving that a
 * Ship Manager can load their own dashboard proves very little; proving that
 * the payload contains none of the other Ship Manager's vessels, and that the
 * other five endpoints refuse them outright, is the actual requirement.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:seastella-dash-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@DisplayName("dashboard authorization")
class DashboardAuthorizationIT {

    private static final String PASSWORD = "SeaStella#Demo2026";

    private static final String ADMIN = "admin@seastella.example";
    private static final String TECH_HEAD_ACME = "tech.head@acme-shipmanagement.example";
    private static final String TECH_HEAD_NORDIC = "tech.head@nordic-tanker.example";
    private static final String SM_ONE = "d.fernandes@acme-shipmanagement.example";
    private static final String SM_TWO = "k.oyelaran@acme-shipmanagement.example";
    private static final String CAPTAIN_KESTREL = "master.kestrel@acme-shipmanagement.example";
    private static final String COORDINATOR = "coordinator@seastella.example";
    private static final String ENGINEER_ONE = "t.okafor@marine-electronics.example";
    private static final String ENGINEER_TWO = "s.nakamura@marine-electronics.example";

    private static final List<String> ALL_ENDPOINTS = List.of(
            "/api/v1/dashboards/platform-admin",
            "/api/v1/dashboards/technical-head",
            "/api/v1/dashboards/ship-manager",
            "/api/v1/dashboards/captain",
            "/api/v1/dashboards/service-coordinator",
            "/api/v1/dashboards/service-engineer");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    // =====================================================================
    //  Anonymous access
    // =====================================================================

    @Nested
    @DisplayName("unauthenticated access")
    class Anonymous {

        /** S-50: every dashboard endpoint rejects an anonymous caller. */
        @Test
        void everyDashboardEndpointRejectsAnonymousCallers() throws Exception {
            for (String endpoint : ALL_ENDPOINTS) {
                mvc.perform(get(endpoint))
                        .andExpect(status().isUnauthorized());
            }
        }

        @Test
        void aGarbageTokenIsRejected() throws Exception {
            mvc.perform(get("/api/v1/dashboards/captain")
                            .header("Authorization", "Bearer not-a-real-token"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // =====================================================================
    //  Role isolation: each role reaches exactly one dashboard
    // =====================================================================

    @Nested
    @DisplayName("role isolation")
    class RoleIsolation {

        /**
         * S-51: each of the six roles may load its own dashboard and is refused
         * the other five. Sixteen of these thirty-six checks are denials.
         */
        @Test
        void eachRoleReachesItsOwnDashboardAndNoOther() throws Exception {
            record Case(String email, String allowed) {}

            List<Case> cases = List.of(
                    new Case(ADMIN, "/api/v1/dashboards/platform-admin"),
                    new Case(TECH_HEAD_ACME, "/api/v1/dashboards/technical-head"),
                    new Case(SM_ONE, "/api/v1/dashboards/ship-manager"),
                    new Case(CAPTAIN_KESTREL, "/api/v1/dashboards/captain"),
                    new Case(COORDINATOR, "/api/v1/dashboards/service-coordinator"),
                    new Case(ENGINEER_ONE, "/api/v1/dashboards/service-engineer"));

            for (Case c : cases) {
                String token = login(c.email());

                for (String endpoint : ALL_ENDPOINTS) {
                    int expected = endpoint.equals(c.allowed()) ? 200 : 403;

                    mvc.perform(get(endpoint).header("Authorization", "Bearer " + token))
                            .andExpect(result -> assertThat(result.getResponse().getStatus())
                                    .as("%s -> %s", c.email(), endpoint)
                                    .isEqualTo(expected));
                }
            }
        }
    }

    // =====================================================================
    //  Cross-vessel and cross-organization isolation
    // =====================================================================

    @Nested
    @DisplayName("data scoping")
    class DataScoping {

        /** S-02: a Ship Manager's payload contains only their allocated vessels. */
        @Test
        void shipManagerSeesOnlyAllocatedVessels() throws Exception {
            Set<String> one = vesselNamesFrom(
                    getJson("/api/v1/dashboards/ship-manager", login(SM_ONE)));
            Set<String> two = vesselNamesFrom(
                    getJson("/api/v1/dashboards/ship-manager", login(SM_TWO)));

            assertThat(one).containsExactlyInAnyOrder("MV Kestrel Trader", "MV Brahmaputra");
            assertThat(two).containsExactlyInAnyOrder("MV Coral Sentinel", "MV Sable Dawn");

            // The two managers' fleets do not overlap at all.
            assertThat(one).doesNotContainAnyElementsOf(two);
        }

        /** S-03: a Technical Head sees their own organization's fleet only. */
        @Test
        void technicalHeadSeesOnlyOwnOrganizationFleet() throws Exception {
            JsonNode acme = getJson("/api/v1/dashboards/technical-head", login(TECH_HEAD_ACME));
            JsonNode nordic = getJson("/api/v1/dashboards/technical-head", login(TECH_HEAD_NORDIC));

            Set<String> acmeVessels = vesselNamesFrom(acme);
            Set<String> nordicVessels = vesselNamesFrom(nordic);

            assertThat(acmeVessels).containsExactlyInAnyOrder(
                    "MV Kestrel Trader", "MV Brahmaputra", "MV Coral Sentinel", "MV Sable Dawn");
            assertThat(nordicVessels).containsExactlyInAnyOrder(
                    "MT Bergen Spirit", "MT Fjord Pioneer");

            assertThat(acmeVessels).doesNotContainAnyElementsOf(nordicVessels);

            // And no Nordic vessel name appears anywhere in the Acme payload.
            assertThat(acme.toString()).doesNotContain("Bergen Spirit", "Fjord Pioneer");
        }

        /** S-04: a Captain sees exactly one vessel - their own. */
        @Test
        void captainSeesExactlyOneVessel() throws Exception {
            JsonNode body = getJson("/api/v1/dashboards/captain", login(CAPTAIN_KESTREL));

            assertThat(body.path("vessel").path("name").asText())
                    .isEqualTo("MV Kestrel Trader");
            assertThat(body.path("meta").path("vesselsInScope").asInt()).isEqualTo(1);

            // No other vessel appears anywhere in the payload.
            String raw = body.toString();
            assertThat(raw).doesNotContain("MV Brahmaputra", "MV Coral Sentinel",
                    "MV Sable Dawn", "MT Bergen Spirit");
        }

        /** Only the Platform Admin sees more than one organization. */
        @Test
        void platformAdminSeesEveryOrganization() throws Exception {
            JsonNode body = getJson("/api/v1/dashboards/platform-admin", login(ADMIN));

            Set<String> orgs = StreamSupport
                    .stream(body.path("organizations").spliterator(), false)
                    .map(o -> o.path("code").asText())
                    .collect(Collectors.toSet());

            assertThat(orgs).containsExactlyInAnyOrder("ACME", "NORDIC");
        }
    }

    // =====================================================================
    //  Service Provider / Technician job isolation
    // =====================================================================

    @Nested
    @DisplayName("service engineer job isolation")
    class EngineerIsolation {

        /**
         * S-22 / master brief s7.6: an engineer sees their own jobs and no
         * others - not even other jobs on the same vessel.
         */
        @Test
        void engineerSeesOnlyOwnAssignedJobs() throws Exception {
            JsonNode one = getJson("/api/v1/dashboards/service-engineer", login(ENGINEER_ONE));
            JsonNode two = getJson("/api/v1/dashboards/service-engineer", login(ENGINEER_TWO));

            Set<String> jobsOne = requestNumbersFrom(one);
            Set<String> jobsTwo = requestNumbersFrom(two);

            assertThat(jobsOne).isNotEmpty();
            assertThat(jobsTwo).isNotEmpty();
            assertThat(jobsOne).doesNotContainAnyElementsOf(jobsTwo);

            // Cross-check against the database: exactly the rows assigned to them.
            Set<String> expectedOne = Set.copyOf(jdbc.queryForList("""
                    select r.request_number from service_request r
                    join app_user u on u.id = r.assigned_engineer_user_id
                    where u.email = ?
                    """, String.class, ENGINEER_ONE));

            assertThat(jobsOne).isSubsetOf(expectedOne);
        }

        /** The engineer payload carries no organization-wide figures. */
        @Test
        void engineerPayloadHasNoFleetWideData() throws Exception {
            JsonNode body = getJson("/api/v1/dashboards/service-engineer", login(ENGINEER_ONE));

            assertThat(body.has("organizations")).isFalse();
            assertThat(body.has("activityFeed")).isFalse();
            assertThat(body.has("vessels")).isFalse();
        }
    }

    // =====================================================================
    //  Financial boundary (SoW s12)
    // =====================================================================

    @Nested
    @DisplayName("financial boundary")
    class FinancialBoundary {

        /**
         * S-21: the Captain's payload contains no monetary value anywhere.
         *
         * <p>Asserted against the whole serialised response rather than named
         * fields, because the requirement is that no amount reaches the Captain
         * by any route - including one added later.
         */
        @Test
        void captainPayloadContainsNoMonetaryFields() throws Exception {
            JsonNode body = getJson("/api/v1/dashboards/captain", login(CAPTAIN_KESTREL));

            assertThat(fieldNames(body))
                    .as("monetary fields in the Captain payload")
                    .doesNotContain("amount", "invoiceTotals", "invoiceRollup",
                            "acceptedValue", "pendingValue", "finalCost", "currency");

            assertThat(body.path("meta").path("financialsVisible").asBoolean()).isFalse();
        }

        /** S-22: the same for the Service Engineer. */
        @Test
        void engineerPayloadContainsNoMonetaryFields() throws Exception {
            JsonNode body = getJson("/api/v1/dashboards/service-engineer", login(ENGINEER_ONE));

            assertThat(fieldNames(body))
                    .doesNotContain("amount", "invoiceTotals", "finalCost", "currency",
                            "acceptedValue", "pendingValue");

            assertThat(body.path("meta").path("financialsVisible").asBoolean()).isFalse();
        }

        /** The roles SoW s12 does permit can see invoice values. */
        @Test
        void permittedRolesDoSeeInvoiceValues() throws Exception {
            JsonNode shipManager = getJson("/api/v1/dashboards/ship-manager", login(SM_ONE));
            assertThat(shipManager.path("meta").path("financialsVisible").asBoolean()).isTrue();
            assertThat(shipManager.path("invoicesAwaitingAcceptance").path("items").isArray()).isTrue();

            JsonNode techHead = getJson("/api/v1/dashboards/technical-head", login(TECH_HEAD_ACME));
            assertThat(techHead.path("meta").path("financialsVisible").asBoolean()).isTrue();
            assertThat(techHead.path("invoices").has("acceptedValue")).isTrue();
        }

        /**
         * A Ship Manager's invoice queue is still vessel-scoped: seeing money is
         * a capability, and it does not widen which vessels they see it for.
         */
        @Test
        void invoiceVisibilityDoesNotWidenVesselScope() throws Exception {
            JsonNode body = getJson("/api/v1/dashboards/ship-manager", login(SM_ONE));

            for (JsonNode item : body.path("invoicesAwaitingAcceptance").path("items")) {
                assertThat(item.path("vesselName").asText())
                        .isIn("MV Kestrel Trader", "MV Brahmaputra");
            }
        }
    }

    // =====================================================================
    //  helpers
    // =====================================================================

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

    /**
     * Every vessel name anywhere in a payload.
     *
     * <p>Searches the whole tree rather than one known field: the point of
     * these assertions is that an out-of-scope vessel appears nowhere at all,
     * including in a queue item or an activity line someone adds later.
     */
    private static Set<String> vesselNamesFrom(JsonNode node) {
        Set<String> names = node.findValues("vesselName").stream()
                .map(JsonNode::asText)
                .collect(Collectors.toCollection(java.util.HashSet::new));

        // The Captain dashboard carries its single vessel as a card, not a row.
        JsonNode card = node.path("vessel").path("name");
        if (!card.isMissingNode()) {
            names.add(card.asText());
        }
        return names;
    }

    private static Set<String> requestNumbersFrom(JsonNode node) {
        return node.findValues("requestNumber").stream()
                .map(JsonNode::asText)
                .collect(Collectors.toSet());
    }

    /** All field names appearing anywhere in the tree. */
    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        node.forEach(child -> names.addAll(fieldNames(child)));
        return names;
    }
}
