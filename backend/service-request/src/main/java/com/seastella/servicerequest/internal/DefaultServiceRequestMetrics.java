package com.seastella.servicerequest.internal;

import com.seastella.servicerequest.api.Priority;
import com.seastella.servicerequest.api.ServiceRequestAction;
import com.seastella.servicerequest.api.ServiceRequestMetrics;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service-request aggregates and queues.
 *
 * <p>Actor and vessel names are joined in rather than resolved per row: a queue
 * of twenty requests would otherwise issue forty extra lookups, which is how a
 * Coordinator's pipeline board becomes the slowest screen in the product.
 */
@Service
class DefaultServiceRequestMetrics implements ServiceRequestMetrics {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    DefaultServiceRequestMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<ServiceRequestStatus, Long> countsByStatus(Set<Long> vesselIds) {
        Map<ServiceRequestStatus, Long> result = zeroed();
        if (vesselIds.isEmpty()) return result;

        named.query("""
                select status, count(*) as c from service_request
                where vessel_id in (:ids) group by status
                """, new MapSqlParameterSource("ids", vesselIds),
                rs -> { result.put(ServiceRequestStatus.valueOf(rs.getString("status")),
                        rs.getLong("c")); });
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<ServiceRequestStatus, Long> countsByStatusPlatformWide() {
        Map<ServiceRequestStatus, Long> result = zeroed();
        jdbc.query("select status, count(*) as c from service_request group by status",
                rs -> { result.put(ServiceRequestStatus.valueOf(rs.getString("status")),
                        rs.getLong("c")); });
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Priority, Long> openCountsByPriority(Set<Long> vesselIds) {
        Map<Priority, Long> result = new EnumMap<>(Priority.class);
        for (Priority p : Priority.values()) result.put(p, 0L);
        if (vesselIds.isEmpty()) return result;

        named.query("""
                select priority, count(*) as c from service_request
                where vessel_id in (:ids) and closed_at is null group by priority
                """, new MapSqlParameterSource("ids", vesselIds),
                rs -> { result.put(Priority.valueOf(rs.getString("priority")), rs.getLong("c")); });
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RequestSummary> queue(Set<Long> vesselIds,
                                      Collection<ServiceRequestStatus> statuses, int limit) {
        if (vesselIds.isEmpty() || statuses.isEmpty()) return List.of();

        return named.query(REQUEST_SELECT + """
                where r.vessel_id in (:ids) and r.status in (:statuses)
                order by r.priority asc, r.created_at asc
                limit :lim
                """, new MapSqlParameterSource("ids", vesselIds)
                        .addValue("statuses", statuses.stream().map(Enum::name).toList())
                        .addValue("lim", limit),
                (rs, i) -> mapRequest(rs));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RequestSummary> queuePlatformWide(Collection<ServiceRequestStatus> statuses, int limit) {
        if (statuses.isEmpty()) return List.of();

        return named.query(REQUEST_SELECT + """
                where r.status in (:statuses)
                order by r.priority asc, r.created_at asc
                limit :lim
                """, new MapSqlParameterSource("statuses", statuses.stream().map(Enum::name).toList())
                        .addValue("lim", limit),
                (rs, i) -> mapRequest(rs));
    }

    /**
     * An engineer's own jobs. Filtered on the assignment column, never on a
     * vessel set - an engineer's reach is the job set, and widening it to the
     * vessel would expose every other request on the same ship.
     */
    @Override
    @Transactional(readOnly = true)
    public List<RequestSummary> forEngineer(Long engineerUserId,
                                            Collection<ServiceRequestStatus> statuses) {
        if (engineerUserId == null || statuses.isEmpty()) return List.of();

        return named.query(REQUEST_SELECT + """
                where r.assigned_engineer_user_id = :engineerId and r.status in (:statuses)
                order by r.priority asc, r.assigned_at asc
                """, new MapSqlParameterSource("engineerId", engineerUserId)
                        .addValue("statuses", statuses.stream().map(Enum::name).toList()),
                (rs, i) -> mapRequest(rs));
    }

    @Override
    @Transactional(readOnly = true)
    public long countByStatuses(Set<Long> vesselIds, Collection<ServiceRequestStatus> statuses) {
        if (vesselIds.isEmpty() || statuses.isEmpty()) return 0;
        Long n = named.queryForObject("""
                select count(*) from service_request
                where vessel_id in (:ids) and status in (:statuses)
                """, new MapSqlParameterSource("ids", vesselIds)
                        .addValue("statuses", statuses.stream().map(Enum::name).toList()), Long.class);
        return n == null ? 0 : n;
    }

    @Override
    @Transactional(readOnly = true)
    public long openCount(Set<Long> vesselIds) {
        if (vesselIds.isEmpty()) return 0;
        Long n = named.queryForObject(
                "select count(*) from service_request where vessel_id in (:ids) and closed_at is null",
                new MapSqlParameterSource("ids", vesselIds), Long.class);
        return n == null ? 0 : n;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Long> openCountPerVessel(Set<Long> vesselIds) {
        Map<Long, Long> result = new HashMap<>();
        if (vesselIds.isEmpty()) return result;

        named.query("""
                select vessel_id, count(*) as c from service_request
                where vessel_id in (:ids) and closed_at is null group by vessel_id
                """, new MapSqlParameterSource("ids", vesselIds),
                rs -> { result.put(rs.getLong("vessel_id"), rs.getLong("c")); });

        for (Long id : vesselIds) result.putIfAbsent(id, 0L);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public ResolutionSplit resolutionSplit(Set<Long> vesselIds) {
        if (vesselIds.isEmpty()) return new ResolutionSplit(0, 0, 0);

        Map<String, Long> counts = new HashMap<>();
        named.query("""
                select
                  sum(case when status = 'CLOSED_NO_COST' then 1 else 0 end) as no_cost,
                  sum(case when status = 'COMPLETED' then 1 else 0 end) as visit,
                  sum(case when closed_at is null then 1 else 0 end) as open_n
                from service_request where vessel_id in (:ids)
                """, new MapSqlParameterSource("ids", vesselIds), rs -> {
            counts.put("no_cost", rs.getLong("no_cost"));
            counts.put("visit", rs.getLong("visit"));
            counts.put("open_n", rs.getLong("open_n"));
        });

        return new ResolutionSplit(
                counts.getOrDefault("no_cost", 0L),
                counts.getOrDefault("visit", 0L),
                counts.getOrDefault("open_n", 0L));
    }

    /**
     * Averaged in Java rather than SQL: Hibernate 6 needs the temporal unit of
     * {@code timestampdiff} as a literal and the date arithmetic is not
     * portable between PostgreSQL and H2. The row set is closed requests over a
     * short window for a handful of vessels, so the cost is negligible.
     */
    @Override
    @Transactional(readOnly = true)
    public Double averageTurnaroundHours(Set<Long> vesselIds, int overLastDays) {
        if (vesselIds.isEmpty()) return null;

        List<Object[]> rows = named.query("""
                select created_at, closed_at from service_request
                where vessel_id in (:ids) and closed_at is not null
                  and created_at >= :since
                """, new MapSqlParameterSource("ids", vesselIds)
                        .addValue("since", Timestamp.from(
                                Instant.now().minus(overLastDays, ChronoUnit.DAYS))),
                (rs, i) -> new Object[]{rs.getTimestamp(1), rs.getTimestamp(2)});

        if (rows.isEmpty()) return null;

        double totalHours = 0;
        for (Object[] row : rows) {
            Instant from = ((Timestamp) row[0]).toInstant();
            Instant to = ((Timestamp) row[1]).toInstant();
            totalHours += ChronoUnit.MINUTES.between(from, to) / 60.0;
        }
        return Math.round((totalHours / rows.size()) * 10.0) / 10.0;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActivityItem> recentActivity(Set<Long> vesselIds, int limit) {
        if (vesselIds.isEmpty()) return List.of();

        return named.query(ACTIVITY_SELECT + """
                where t.vessel_id in (:ids)
                order by t.occurred_at desc, t.id desc
                limit :lim
                """, new MapSqlParameterSource("ids", vesselIds).addValue("lim", limit),
                (rs, i) -> mapActivity(rs));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActivityItem> recentActivityPlatformWide(int limit) {
        return named.query(ACTIVITY_SELECT + """
                order by t.occurred_at desc, t.id desc
                limit :lim
                """, new MapSqlParameterSource("lim", limit), (rs, i) -> mapActivity(rs));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActivityItem> history(Long serviceRequestId) {
        if (serviceRequestId == null) return List.of();
        return named.query(ACTIVITY_SELECT + """
                where t.service_request_id = :id
                order by t.occurred_at asc, t.id asc
                """, new MapSqlParameterSource("id", serviceRequestId), (rs, i) -> mapActivity(rs));
    }

    @Override
    @Transactional(readOnly = true)
    public CompletionSummary completionReport(Long serviceRequestId) {
        if (serviceRequestId == null) return null;

        List<CompletionSummary> rows = named.query("""
                select cr.service_request_id, cr.engineer_user_id, u.full_name as engineer_name,
                       cr.work_performed, cr.parts_used, cr.outcome, cr.final_cost,
                       cr.cost_variance, cr.relay_note, cr.reported_at, cr.reconciled_at
                from completion_report cr
                join app_user u on u.id = cr.engineer_user_id
                where cr.service_request_id = :id
                """, new MapSqlParameterSource("id", serviceRequestId), (rs, i) -> {
            java.math.BigDecimal variance = rs.getBigDecimal("cost_variance");
            java.math.BigDecimal cost = rs.getBigDecimal("final_cost");
            return new CompletionSummary(
                    rs.getLong("service_request_id"), rs.getLong("engineer_user_id"),
                    rs.getString("engineer_name"), rs.getString("work_performed"),
                    rs.getString("parts_used"), rs.getString("outcome"),
                    cost == null ? null : cost.toPlainString(),
                    variance == null ? null : variance.toPlainString(),
                    variance != null && variance.signum() != 0,
                    rs.getString("relay_note"),
                    instant(rs, "reported_at"), instant(rs, "reconciled_at"));
        });
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static final String REQUEST_SELECT = """
            select r.id, r.request_number, r.vessel_id, v.name as vessel_name,
                   r.spare_id, s.name as spare_name, s.path as spare_path,
                   c.code as cat_code, r.title, r.priority, r.status,
                   r.raised_by_user_id, ru.full_name as raised_by_name,
                   r.assigned_engineer_user_id, eu.full_name as engineer_name,
                   r.created_at, r.updated_at
            from service_request r
            join vessel v on v.id = r.vessel_id
            join spare s on s.id = r.spare_id
            join equipment_category c on c.id = s.equipment_category_id
            join app_user ru on ru.id = r.raised_by_user_id
            left join app_user eu on eu.id = r.assigned_engineer_user_id
            """;

    private static final String ACTIVITY_SELECT = """
            select t.service_request_id, r.request_number, t.vessel_id, v.name as vessel_name,
                   t.from_status, t.to_status, t.action, t.actor_user_id,
                   au.full_name as actor_name, t.actor_role, t.reason, t.occurred_at, t.id
            from service_request_transition t
            join service_request r on r.id = t.service_request_id
            join vessel v on v.id = t.vessel_id
            left join app_user au on au.id = t.actor_user_id
            """;

    private static RequestSummary mapRequest(ResultSet rs) throws SQLException {
        ServiceRequestStatus status = ServiceRequestStatus.valueOf(rs.getString("status"));
        Instant raisedAt = instant(rs, "created_at");
        Object engineerId = rs.getObject("assigned_engineer_user_id");

        return new RequestSummary(
                rs.getLong("id"), rs.getString("request_number"),
                rs.getLong("vessel_id"), rs.getString("vessel_name"),
                rs.getLong("spare_id"), rs.getString("spare_name"), rs.getString("spare_path"),
                rs.getString("cat_code"), rs.getString("title"),
                Priority.valueOf(rs.getString("priority")), status, status.label(),
                rs.getLong("raised_by_user_id"), rs.getString("raised_by_name"),
                engineerId == null ? null : ((Number) engineerId).longValue(),
                rs.getString("engineer_name"),
                raisedAt, instant(rs, "updated_at"),
                raisedAt == null ? null : (int) ChronoUnit.DAYS.between(raisedAt, Instant.now()));
    }

    private static ActivityItem mapActivity(ResultSet rs) throws SQLException {
        String from = rs.getString("from_status");
        ServiceRequestAction action = ServiceRequestAction.valueOf(rs.getString("action"));
        Object actorId = rs.getObject("actor_user_id");

        return new ActivityItem(
                rs.getLong("service_request_id"), rs.getString("request_number"),
                rs.getLong("vessel_id"), rs.getString("vessel_name"),
                from == null ? null : ServiceRequestStatus.valueOf(from),
                ServiceRequestStatus.valueOf(rs.getString("to_status")),
                action, action.label(),
                actorId == null ? null : ((Number) actorId).longValue(),
                rs.getString("actor_name"), rs.getString("actor_role"),
                rs.getString("reason"), instant(rs, "occurred_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Map<ServiceRequestStatus, Long> zeroed() {
        Map<ServiceRequestStatus, Long> m = new EnumMap<>(ServiceRequestStatus.class);
        for (ServiceRequestStatus s : ServiceRequestStatus.values()) m.put(s, 0L);
        return m;
    }
}
