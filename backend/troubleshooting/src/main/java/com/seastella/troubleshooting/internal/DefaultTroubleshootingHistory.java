package com.seastella.troubleshooting.internal;

import com.seastella.troubleshooting.api.TroubleshootingHistory;
import com.seastella.troubleshooting.api.TroubleshootingOutcome;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Set;

/**
 * Guided-check sessions as report rows (RPT-03).
 *
 * <p>Read in SQL rather than by loading sessions and their answers: a report
 * over a fleet's whole history should not build an object graph per request.
 * The answer count comes from the response table, so it counts what was
 * actually recorded.
 */
@Component
class DefaultTroubleshootingHistory implements TroubleshootingHistory {

    private final NamedParameterJdbcTemplate jdbc;

    DefaultTroubleshootingHistory(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionSummary> sessions(Set<Long> vesselIds, int limit) {
        if (vesselIds == null || vesselIds.isEmpty()) return List.of();

        return jdbc.query("""
                select s.service_request_id, r.request_number, s.vessel_id, v.name as vessel_name,
                       sp.name as spare_name, pt.label as problem_type, f.name as flow_name,
                       s.flow_version, f.content_source, s.status, s.outcome,
                       s.root_cause_note, s.temporary_fix_note, u.full_name as run_by,
                       s.started_at, s.completed_at,
                       (select count(*) from troubleshooting_response tr where tr.session_id = s.id) as answer_count
                from troubleshooting_session s
                join troubleshooting_flow f on f.id = s.flow_id
                join service_request r on r.id = s.service_request_id
                join vessel v on v.id = s.vessel_id
                join spare sp on sp.id = r.spare_id
                left join problem_type pt on pt.id = r.problem_type_id
                join app_user u on u.id = s.started_by_user_id
                where s.vessel_id in (:ids)
                order by s.started_at desc
                limit :lim
                """, new MapSqlParameterSource("ids", vesselIds).addValue("lim", limit),
                (rs, i) -> new SessionSummary(
                        rs.getLong("service_request_id"), rs.getString("request_number"),
                        rs.getLong("vessel_id"), rs.getString("vessel_name"), rs.getString("spare_name"),
                        rs.getString("problem_type"), rs.getString("flow_name"), rs.getInt("flow_version"),
                        "SAMPLE".equals(rs.getString("content_source")), rs.getString("status"),
                        rs.getString("outcome") == null ? null : TroubleshootingOutcome.valueOf(rs.getString("outcome")),
                        rs.getInt("answer_count"), rs.getString("root_cause_note"), rs.getString("temporary_fix_note"),
                        rs.getString("run_by"), instant(rs.getTimestamp("started_at")),
                        instant(rs.getTimestamp("completed_at"))));
    }

    private static java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
