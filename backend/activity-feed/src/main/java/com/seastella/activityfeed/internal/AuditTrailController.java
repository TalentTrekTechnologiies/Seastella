package com.seastella.activityfeed.internal;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * The audit trail itself, unfiltered (SoW §8.5).
 *
 * <p>"Full audit-trail access (not limited to configuration actions)." The
 * activity feed is a <em>readable</em> view of this table and deliberately
 * leaves things out — sign-ins, and every individual answer to a guided check —
 * because in a feed they bury the events a Platform Admin is watching for. That
 * is the right choice for a feed and the wrong one for an audit trail, so this
 * endpoint applies no such filter: every row, newest first, with the before and
 * after values as they were recorded.
 *
 * <p>Platform Admin only, and read-only: nothing anywhere in the platform can
 * edit or delete an audit row, which is what makes the trail worth keeping.
 */
@RestController
@RequestMapping("/api/v1/audit")
class AuditTrailController {

    private final NamedParameterJdbcTemplate jdbc;

    AuditTrailController(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Transactional(readOnly = true)
    ResponseEntity<Page> entries(@RequestParam(required = false) String action,
                                 @RequestParam(required = false) String entityType,
                                 @RequestParam(required = false) Long entityId,
                                 @RequestParam(required = false) Long actorUserId,
                                 @RequestParam(required = false) Long organizationId,
                                 @RequestParam(required = false) Long before,
                                 @RequestParam(defaultValue = "100") int limit) {
        int size = Math.min(Math.max(limit, 1), 500);
        StringBuilder sql = new StringBuilder("""
                select a.id, a.action, a.entity_type, a.entity_id, a.actor_user_id, a.actor_role,
                       a.organization_id, a.vessel_id, a.before_value, a.after_value,
                       a.ip_address, a.occurred_at,
                       u.full_name as actor_name, o.name as organization_name, v.name as vessel_name
                from audit_entry a
                left join app_user u    on u.id = a.actor_user_id
                left join organization o on o.id = a.organization_id
                left join vessel v       on v.id = a.vessel_id
                where 1 = 1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("lim", size);
        if (action != null && !action.isBlank()) {
            sql.append(" and a.action = :action");
            params.addValue("action", action.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (entityType != null && !entityType.isBlank()) {
            sql.append(" and a.entity_type = :entityType");
            params.addValue("entityType", entityType.trim());
        }
        if (entityId != null) {
            sql.append(" and a.entity_id = :entityId");
            params.addValue("entityId", entityId);
        }
        if (actorUserId != null) {
            sql.append(" and a.actor_user_id = :actorUserId");
            params.addValue("actorUserId", actorUserId);
        }
        if (organizationId != null) {
            sql.append(" and a.organization_id = :organizationId");
            params.addValue("organizationId", organizationId);
        }
        // Keyset paging on the id: an offset would skip or repeat rows as new
        // entries land, and entries land constantly.
        if (before != null) {
            sql.append(" and a.id < :before");
            params.addValue("before", before);
        }
        sql.append(" order by a.id desc limit :lim");

        List<Entry> rows = jdbc.query(sql.toString(), params, (rs, i) -> toEntry(rs));
        List<String> actions = jdbc.getJdbcTemplate().queryForList(
                "select distinct action from audit_entry order by action", String.class);
        Long next = rows.size() == size ? rows.get(rows.size() - 1).id() : null;
        return ResponseEntity.ok(new Page(rows, next, actions, total()));
    }

    private long total() {
        Long n = jdbc.getJdbcTemplate().queryForObject("select count(*) from audit_entry", Long.class);
        return n == null ? 0 : n;
    }

    private static Entry toEntry(ResultSet rs) throws SQLException {
        Timestamp at = rs.getTimestamp("occurred_at");
        return new Entry(
                rs.getLong("id"),
                rs.getString("action"),
                rs.getString("entity_type"),
                (Long) rs.getObject("entity_id"),
                (Long) rs.getObject("actor_user_id"),
                rs.getString("actor_name"),
                rs.getString("actor_role"),
                rs.getString("organization_name"),
                rs.getString("vessel_name"),
                rs.getString("before_value"),
                rs.getString("after_value"),
                rs.getString("ip_address"),
                at == null ? null : at.toInstant());
    }

    record Entry(Long id, String action, String entityType, Long entityId, Long actorUserId, String actorName,
                 String actorRole, String organizationName, String vesselName, String beforeValue,
                 String afterValue, String ipAddress, Instant occurredAt) {}

    record Page(List<Entry> items, Long nextBefore, List<String> actions, long total) {}
}
