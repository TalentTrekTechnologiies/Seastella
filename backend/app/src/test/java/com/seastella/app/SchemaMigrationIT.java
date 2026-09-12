package com.seastella.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the migrations run on a clean database and that the schema they
 * produce matches the entity model.
 *
 * <p>The strongest assertion here is implicit: the context only starts if
 * Hibernate's {@code ddl-auto: validate} agrees with every mapping. A column
 * that a migration forgot, or a type that drifted, fails this test rather than
 * surfacing at runtime on the first query that touches it.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:seastella-schema-it;MODE=PostgreSQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@DisplayName("schema migrations")
class SchemaMigrationIT {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("every module's tables exist after a clean migration")
    void allTablesArePresent() throws Exception {
        List<String> tables = tableNames();

        assertThat(tables).contains(
                // platform-core
                "audit_entry",
                // fleet
                "organization", "vessel", "equipment_category", "spare", "replacement_part",
                // identity-access
                "app_user", "user_vessel_assignment",
                // maintenance
                "maintenance_threshold", "spare_maintenance_rule",
                // service-request
                "problem_type", "service_request", "service_request_transition", "completion_report",
                // invoice
                "invoice");
    }

    @Test
    @DisplayName("flyway recorded each module's migration")
    void migrationHistoryIsComplete() {
        // Flyway records its own schema-creation step with a null version;
        // only the real migrations are asserted here.
        List<String> versions = jdbc.queryForList(
                """
                select version from flyway_schema_history
                where success = true and version is not null
                order by installed_rank
                """,
                String.class);

        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6");
    }

    @Test
    @DisplayName("a spare tree cannot span two vessels")
    void spareTreeCannotSpanVessels() {
        long orgId = insertOrganization("CROSSVESSEL", "Cross Vessel Test Ltd");
        long vesselA = insertVessel(orgId, "MV Alpha", "9100001");
        long vesselB = insertVessel(orgId, "MV Bravo", "9100002");
        long categoryId = insertCategory("TEST_RADAR", "Test Radar");

        long parentOnA = insertSpare(vesselA, categoryId, "13.1", "X-Band Radar", null);

        // A child on vessel B claiming a parent on vessel A has no matching
        // (id, vessel_id) pair, so the composite foreign key rejects it. This
        // is SPR-10 / security test S-07 - a cross-vessel child would be a
        // silent route around vessel isolation.
        assertThatThrownBy(() -> insertSpare(vesselB, categoryId, "13.1.2", "Display Fan", parentOnA))
                .isInstanceOf(Exception.class);

        // The same child under a parent on its own vessel is fine.
        long parentOnB = insertSpare(vesselB, categoryId, "13.1", "X-Band Radar", null);
        long childOnB = insertSpare(vesselB, categoryId, "13.1.2", "Display Fan", parentOnB);
        assertThat(childOnB).isPositive();
    }

    @Test
    @DisplayName("a tenant user must belong to an organization")
    void nonPlatformAdminRequiresAnOrganization() {
        // ck_app_user_org_scope: everyone but the Platform Admin is scoped.
        assertThatThrownBy(() -> jdbc.update("""
                insert into app_user
                  (email, password_hash, full_name, role, organization_id, status,
                   failed_login_count, created_at, version)
                values (?, ?, ?, ?, null, 'ACTIVE', 0, current_timestamp, 0)
                """, "unscoped@example.com", "x", "Unscoped Captain", "CAPTAIN"))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("a platform admin must not belong to one")
    void platformAdminMustNotHaveAnOrganization() {
        long orgId = insertOrganization("ADMINSCOPE", "Admin Scope Test Ltd");

        assertThatThrownBy(() -> jdbc.update("""
                insert into app_user
                  (email, password_hash, full_name, role, organization_id, status,
                   failed_login_count, created_at, version)
                values (?, ?, ?, 'PLATFORM_ADMIN', ?, 'ACTIVE', 0, current_timestamp, 0)
                """, "scopedadmin@example.com", "x", "Scoped Admin", orgId))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("dashboard query indexes exist")
    void dashboardIndexesArePresent() throws Exception {
        List<String> indexes = indexNames();

        // These back the aggregates every role dashboard runs.
        assertThat(indexes).contains(
                "ix_sr_vessel_status",
                "ix_sr_engineer",
                "ix_rule_vessel_due",
                "ix_invoice_request_status",
                "ix_spare_vessel_category",
                "ix_audit_occurred");
    }

    private List<String> tableNames() throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    names.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    private List<String> indexNames() throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            for (String table : List.of("service_request", "spare_maintenance_rule",
                    "invoice", "spare", "audit_entry")) {
                // H2 runs with DATABASE_TO_LOWER, and PostgreSQL folds to
                // lower case too, so metadata is queried in lower case.
                try (ResultSet rs = md.getIndexInfo(null, null, table, false, false)) {
                    while (rs.next()) {
                        String n = rs.getString("INDEX_NAME");
                        if (n != null) names.add(n.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return names;
    }

    private long insertOrganization(String code, String name) {
        jdbc.update("""
                insert into organization (name, code, status, created_at, version)
                values (?, ?, 'ACTIVE', current_timestamp, 0)
                """, name, code);
        return jdbc.queryForObject("select id from organization where code = ?", Long.class, code);
    }

    private long insertVessel(long orgId, String name, String imo) {
        jdbc.update("""
                insert into vessel (organization_id, name, imo_number, status, created_at, version)
                values (?, ?, ?, 'ACTIVE', current_timestamp, 0)
                """, orgId, name, imo);
        return jdbc.queryForObject("select id from vessel where imo_number = ?", Long.class, imo);
    }

    private long insertCategory(String code, String name) {
        jdbc.update("""
                insert into equipment_category (code, name, display_order, created_at, version)
                values (?, ?, 99, current_timestamp, 0)
                """, code, name);
        return jdbc.queryForObject("select id from equipment_category where code = ?", Long.class, code);
    }

    private long insertSpare(long vesselId, long categoryId, String path, String name, Long parentId) {
        jdbc.update("""
                insert into spare
                  (vessel_id, equipment_category_id, parent_spare_id, path, depth, vmp_ref, name,
                   tracks_running_hours, criticality, status, created_at, version)
                values (?, ?, ?, ?, ?, ?, ?, false, 'MEDIUM', 'OPERATIONAL', current_timestamp, 0)
                """, vesselId, categoryId, parentId, path,
                (short) path.chars().filter(ch -> ch == '.').count(), path, name);

        return jdbc.queryForObject(
                "select id from spare where vessel_id = ? and path = ?", Long.class, vesselId, path);
    }
}
