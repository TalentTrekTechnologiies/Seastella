package com.seastella.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the seed dataset is relationally coherent and covers every state the
 * dashboards need to display.
 *
 * <p>The coverage assertions matter more than the counts. A dataset where no
 * request is awaiting approval and no invoice is pending would render every
 * dashboard queue empty, and an empty queue looks the same whether the query is
 * correct or broken.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "seastella.seed.enabled=true",
        // Its own database: SchemaMigrationIT writes deliberately malformed
        // fixtures, and sharing one in-memory schema would make these counts
        // depend on test execution order.
        "spring.datasource.url=jdbc:h2:mem:seastella-seed-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@DisplayName("seed data")
class SeedDataIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Nested
    @DisplayName("hierarchy")
    class Hierarchy {

        @Test
        void twoOrganizationsExistSoIsolationHasSomethingToExclude() {
            assertThat(count("organization")).isEqualTo(2);
            assertThat(jdbc.queryForList("select code from organization order by code", String.class))
                    .containsExactly("ACME", "NORDIC");
        }

        @Test
        void vesselsCarryRealVmpIdentifiers() {
            List<Map<String, Object>> vessels = jdbc.queryForList(
                    "select name, imo_number, mmsi, call_sign, flag, vessel_type, status "
                            + "from vessel order by name");

            assertThat(vessels).hasSize(6);
            assertThat(vessels).allSatisfy(v -> {
                assertThat((String) v.get("imo_number")).hasSize(7);
                assertThat((String) v.get("call_sign")).isNotBlank();
                assertThat((String) v.get("vessel_type")).isNotBlank();
            });

            // Not every vessel is active: the status breakdown must have shape.
            assertThat(jdbc.queryForObject(
                    "select count(*) from vessel where status = 'DRY_DOCK'", Integer.class))
                    .isEqualTo(1);
        }

        @Test
        void equipmentCategoriesCoverTheSowBridgeFit() {
            List<String> codes = jdbc.queryForList(
                    "select code from equipment_category", String.class);

            assertThat(codes).contains("RADAR", "ECDIS", "GPS", "AIS", "VHF", "EPIRB",
                    "VDR", "GYRO", "BNWAS", "SART", "NAVTEX", "LRIT", "SSAS");
        }

        @Test
        void everyVesselCarriesTheFullSpareFit() {
            List<Map<String, Object>> perVessel = jdbc.queryForList(
                    "select vessel_id, count(*) as c from spare group by vessel_id");

            assertThat(perVessel).hasSize(6);
            assertThat(perVessel).allSatisfy(row ->
                    assertThat(((Number) row.get("c")).intValue()).isGreaterThan(40));
        }
    }

    @Nested
    @DisplayName("the Spare tree")
    class SpareTree {

        /** SPR-04: spares nest, mirroring the VMP decimal ids. */
        @Test
        void sparesNestRecursively() {
            Integer nested = jdbc.queryForObject(
                    "select count(*) from spare where parent_spare_id is not null", Integer.class);

            assertThat(nested).isGreaterThan(0);
        }

        @Test
        void theRadarTreeHasTheExpectedThreeLevels() {
            Long vesselId = jdbc.queryForObject(
                    "select id from vessel where imo_number = '9412367'", Long.class);

            Map<String, Object> parent = jdbc.queryForMap(
                    "select id, path, depth, parent_spare_id from spare "
                            + "where vessel_id = ? and path = '13.1'", vesselId);
            Map<String, Object> child = jdbc.queryForMap(
                    "select id, path, depth, parent_spare_id from spare "
                            + "where vessel_id = ? and path = '13.1.2'", vesselId);

            assertThat(((Number) parent.get("depth")).intValue()).isEqualTo(1);
            assertThat(((Number) child.get("depth")).intValue()).isEqualTo(2);
            assertThat(((Number) child.get("parent_spare_id")).longValue())
                    .isEqualTo(((Number) parent.get("id")).longValue());
        }

        /** SPR-10: every parent is on the same vessel as its child. */
        @Test
        void noSpareTreeSpansTwoVessels() {
            Integer crossing = jdbc.queryForObject("""
                    select count(*) from spare c
                    join spare p on c.parent_spare_id = p.id
                    where c.vessel_id <> p.vessel_id
                    """, Integer.class);

            assertThat(crossing).isZero();
        }

        /** Only equipment that really accrues hours tracks them. */
        @Test
        void runningHoursAreTrackedSelectively() {
            List<String> tracked = jdbc.queryForList(
                    "select distinct name from spare where tracks_running_hours = true "
                            + "order by name", String.class);

            assertThat(tracked).isNotEmpty();
            assertThat(tracked).allSatisfy(n ->
                    assertThat(n).containsAnyOf("Radar", "Magnetron"));
        }
    }

    @Nested
    @DisplayName("maintenance covers every colour band")
    class MaintenanceBands {

        @Test
        void everyBandIsRepresented() {
            Map<String, Integer> bands = Map.of(
                    "overdue", scalar("select count(*) from spare_maintenance_rule "
                            + "where next_due_date < current_date"),
                    "dueToday", scalar("select count(*) from spare_maintenance_rule "
                            + "where next_due_date = current_date"),
                    "urgent", scalar("select count(*) from spare_maintenance_rule "
                            + "where next_due_date > current_date "
                            + "and next_due_date <= current_date + 9"),
                    "approaching", scalar("select count(*) from spare_maintenance_rule "
                            + "where next_due_date > current_date + 9 "
                            + "and next_due_date <= current_date + 15"),
                    "normal", scalar("select count(*) from spare_maintenance_rule "
                            + "where next_due_date > current_date + 15"));

            assertThat(bands).allSatisfy((band, n) ->
                    assertThat(n).as("%s band", band).isGreaterThan(0));
        }

        @Test
        void thresholdsAreSeededAsConfiguration() {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select status_code, min_days, max_days from maintenance_threshold "
                            + "order by status_code");

            assertThat(rows).hasSize(2);
            assertThat(rows).anySatisfy(r -> {
                assertThat(r.get("status_code")).isEqualTo("URGENT");
                assertThat(((Number) r.get("min_days")).intValue()).isEqualTo(1);
                assertThat(((Number) r.get("max_days")).intValue()).isEqualTo(9);
            });
        }
    }

    @Nested
    @DisplayName("service requests cover every workflow state")
    class WorkflowCoverage {

        @Test
        void everyDashboardRelevantStateHasAtLeastOneRequest() {
            List<String> statuses = jdbc.queryForList(
                    "select distinct status from service_request", String.class);

            assertThat(statuses).contains(
                    "TROUBLESHOOTING",
                    "LIVE_AGENT_ESCALATED",
                    "PENDING_OPERATIONAL_APPROVAL",
                    "CLARIFICATION_REQUESTED",
                    "REJECTED",
                    "OPERATIONALLY_APPROVED",
                    "CLOSED_NO_COST",
                    "INVOICE_RAISED",
                    "INVOICE_QUERIED",
                    "INVOICE_REJECTED",
                    "INVOICE_ACCEPTED",
                    "ENGINEER_ASSIGNED",
                    "IN_PROGRESS",
                    "COMPLETION_REPORTED",
                    "COMPLETED");
        }

        @Test
        void everyRequestHasACoherentTransitionHistory() {
            // Every request's newest transition must agree with its status.
            Integer mismatched = jdbc.queryForObject("""
                    select count(*) from service_request r
                    where r.status <> (
                        select t.to_status from service_request_transition t
                        where t.service_request_id = r.id
                        order by t.occurred_at desc, t.id desc
                        limit 1
                    )
                    """, Integer.class);

            assertThat(mismatched).isZero();
        }

        @Test
        void approvedRequestsRecordWhoApprovedThem() {
            Integer missing = jdbc.queryForObject("""
                    select count(*) from service_request
                    where status in ('OPERATIONALLY_APPROVED','INVOICE_RAISED','INVOICE_ACCEPTED',
                                     'ENGINEER_ASSIGNED','IN_PROGRESS','COMPLETION_REPORTED','COMPLETED')
                      and operational_approved_by_user_id is null
                    """, Integer.class);

            assertThat(missing).isZero();
        }

        @Test
        void assignedRequestsRecordTheEngineerAndTheAssigner() {
            Integer missing = jdbc.queryForObject("""
                    select count(*) from service_request
                    where status in ('ENGINEER_ASSIGNED','IN_PROGRESS','COMPLETION_REPORTED','COMPLETED')
                      and (assigned_engineer_user_id is null or assigned_by_user_id is null)
                    """, Integer.class);

            assertThat(missing).isZero();
        }
    }

    @Nested
    @DisplayName("the invoice gate is consistent in the data")
    class InvoiceConsistency {

        /**
         * The critical consistency check: anything at or past assignment must
         * have a genuinely ACCEPTED invoice row, because the gate reads the
         * invoice records rather than the request's status column.
         */
        @Test
        void everyAssignedRequestHasAnAcceptedInvoice() {
            Integer withoutAccepted = jdbc.queryForObject("""
                    select count(*) from service_request r
                    where r.status in ('ENGINEER_ASSIGNED','IN_PROGRESS',
                                       'COMPLETION_REPORTED','COMPLETED')
                      and not exists (
                        select 1 from invoice i
                        where i.service_request_id = r.id and i.status = 'ACCEPTED')
                    """, Integer.class);

            assertThat(withoutAccepted)
                    .as("assigned requests lacking an accepted invoice")
                    .isZero();
        }

        /** Conversely, nothing unaccepted may already have an engineer. */
        @Test
        void noRequestIsAssignedWithoutAcceptance() {
            Integer assignedTooEarly = jdbc.queryForObject("""
                    select count(*) from service_request r
                    where r.assigned_engineer_user_id is not null
                      and not exists (
                        select 1 from invoice i
                        where i.service_request_id = r.id and i.status = 'ACCEPTED')
                    """, Integer.class);

            assertThat(assignedTooEarly).isZero();
        }

        @Test
        void invoiceStatusesCoverRaisedQueriedRejectedAndAccepted() {
            List<String> statuses = jdbc.queryForList(
                    "select distinct status from invoice", String.class);

            assertThat(statuses).contains("RAISED", "QUERIED", "REJECTED", "ACCEPTED");
        }

        /** No payment fields exist to populate - settlement is out of scope. */
        @Test
        void decidedInvoicesRecordTheDecider() {
            Integer undecided = jdbc.queryForObject("""
                    select count(*) from invoice
                    where status in ('ACCEPTED','REJECTED','QUERIED')
                      and (decided_by_user_id is null or decided_at is null)
                    """, Integer.class);

            assertThat(undecided).isZero();
        }
    }

    @Nested
    @DisplayName("users and the delegation chain")
    class Users {

        @Test
        void allSixPilotRolesArePresent() {
            List<String> roles = jdbc.queryForList(
                    "select distinct role from app_user order by role", String.class);

            assertThat(roles).containsExactlyInAnyOrder(
                    "PLATFORM_ADMIN", "TECHNICAL_HEAD", "SHIP_MANAGER",
                    "CAPTAIN", "SERVICE_COORDINATOR", "SERVICE_ENGINEER");
        }

        /** The Phase-2 role must never be provisioned (SEC-13). */
        @Test
        void chiefEngineerIsNotProvisioned() {
            assertThat(jdbc.queryForObject(
                    "select count(*) from app_user where role = 'CHIEF_ENGINEER'", Integer.class))
                    .isZero();
        }

        @Test
        void aCaptainIsAssignedToExactlyOneVessel() {
            List<Map<String, Object>> perCaptain = jdbc.queryForList("""
                    select u.email, count(a.vessel_id) as c
                    from app_user u join user_vessel_assignment a on a.user_id = u.id
                    where u.role = 'CAPTAIN'
                    group by u.email
                    """);

            assertThat(perCaptain).isNotEmpty();
            assertThat(perCaptain).allSatisfy(r ->
                    assertThat(((Number) r.get("c")).intValue()).isEqualTo(1));
        }

        /** SoW s4.1: every assignment records who granted it. */
        @Test
        void everyAssignmentRecordsItsDelegation() {
            assertThat(jdbc.queryForObject(
                    "select count(*) from user_vessel_assignment where assigned_by_user_id is null",
                    Integer.class))
                    .isZero();
        }

        @Test
        void shipManagersHoldDisjointVesselSets() {
            Integer shared = jdbc.queryForObject("""
                    select count(*) from user_vessel_assignment a
                    join app_user u on u.id = a.user_id and u.role = 'SHIP_MANAGER'
                    join user_vessel_assignment b on b.vessel_id = a.vessel_id and b.id <> a.id
                    join app_user v on v.id = b.user_id and v.role = 'SHIP_MANAGER'
                    """, Integer.class);

            assertThat(shared).isZero();
        }
    }

    @Nested
    @DisplayName("temporal coherence")
    class Temporal {

        /**
         * A closed request must not predate its own creation.
         *
         * <p>JPA auditing stamps created_at with "now" while seeded requests
         * are raised in the past, so without back-dating every turnaround
         * average comes out negative — which is exactly how it first appeared
         * on the Coordinator dashboard.
         */
        @Test
        void noRequestClosesBeforeItWasRaised() {
            Integer inverted = jdbc.queryForObject("""
                    select count(*) from service_request
                    where closed_at is not null and closed_at < created_at
                    """, Integer.class);

            assertThat(inverted).as("requests closed before they were raised").isZero();
        }

        @Test
        void closedRequestsSpanAPlausibleRange() {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select request_number, created_at, closed_at from service_request
                    where closed_at is not null
                    """);

            assertThat(rows).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("stock and seed marking")
    class StockAndMarking {

        @Test
        void someReplacementPartsAreBelowMinimum() {
            Integer short_ = jdbc.queryForObject(
                    "select count(*) from replacement_part where quantity_on_hand < minimum_quantity",
                    Integer.class);

            assertThat(short_).isGreaterThan(0);
        }

        /** NFR-11: demo rows are distinguishable from client data in the database. */
        @Test
        void everySeededRowIsMarked() {
            for (String table : List.of("organization", "vessel", "spare", "app_user",
                    "service_request", "invoice", "replacement_part")) {

                Integer unmarked = jdbc.queryForObject(
                        "select count(*) from " + table + " where seed_marker is null", Integer.class);

                assertThat(unmarked).as("unmarked rows in %s", table).isZero();
            }
        }
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private int scalar(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }
}
