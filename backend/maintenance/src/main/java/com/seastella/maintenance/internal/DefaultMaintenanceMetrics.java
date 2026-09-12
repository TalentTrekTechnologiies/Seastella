package com.seastella.maintenance.internal;

import com.seastella.maintenance.api.DueStatus;
import com.seastella.maintenance.api.MaintenanceMetrics;
import com.seastella.maintenance.api.MaintenanceStatusEngine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maintenance aggregates.
 *
 * <p>The banding is <b>not</b> re-implemented in SQL. Rows come back with a raw
 * day count and every one is classified through
 * {@link MaintenanceStatusEngine#classify}, so a dashboard count and a spare
 * badge are the same rule applied to the same number. A {@code CASE WHEN
 * days <= 9} in this query would be a second copy of the threshold logic that
 * configuration changes would silently fail to update.
 */
@Service
class DefaultMaintenanceMetrics implements MaintenanceMetrics {

    private final NamedParameterJdbcTemplate named;
    private final MaintenanceStatusEngine engine;

    DefaultMaintenanceMetrics(JdbcTemplate jdbc, MaintenanceStatusEngine engine) {
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.engine = engine;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<DueStatus, Long> dueBreakdown(Set<Long> vesselIds) {
        Map<DueStatus, Long> result = new EnumMap<>(DueStatus.class);
        for (DueStatus s : DueStatus.values()) result.put(s, 0L);
        if (vesselIds.isEmpty()) return result;

        LocalDate today = LocalDate.now();

        // One pass over the active rules; each classified by the engine.
        named.query("""
                select r.vessel_id, r.next_due_date, v.organization_id
                from spare_maintenance_rule r
                join vessel v on v.id = r.vessel_id
                where r.vessel_id in (:ids) and r.active = true
                """, new MapSqlParameterSource("ids", vesselIds), rs -> {
            Date due = rs.getDate("next_due_date");
            if (due == null) {
                result.merge(DueStatus.NOT_TRACKED, 1L, Long::sum);
                return;
            }
            int days = (int) ChronoUnit.DAYS.between(today, due.toLocalDate());
            result.merge(engine.classify(days, rs.getLong("organization_id")), 1L, Long::sum);
        });
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DueItem> dueSoon(Set<Long> vesselIds, int withinDays, int limit) {
        if (vesselIds.isEmpty()) return List.of();
        LocalDate today = LocalDate.now();

        return named.query(DUE_SELECT + """
                where r.vessel_id in (:ids) and r.active = true
                  and r.next_due_date is not null
                  and r.next_due_date >= :today and r.next_due_date <= :cutoff
                order by r.next_due_date asc
                limit :lim
                """, new MapSqlParameterSource("ids", vesselIds)
                        .addValue("today", Date.valueOf(today))
                        .addValue("cutoff", Date.valueOf(today.plusDays(withinDays)))
                        .addValue("lim", limit),
                (rs, i) -> mapDueItem(rs, today));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DueItem> overdue(Set<Long> vesselIds, int limit) {
        if (vesselIds.isEmpty()) return List.of();
        LocalDate today = LocalDate.now();

        return named.query(DUE_SELECT + """
                where r.vessel_id in (:ids) and r.active = true
                  and r.next_due_date is not null and r.next_due_date < :today
                order by r.next_due_date asc
                limit :lim
                """, new MapSqlParameterSource("ids", vesselIds)
                        .addValue("today", Date.valueOf(today))
                        .addValue("lim", limit),
                (rs, i) -> mapDueItem(rs, today));
    }

    @Override
    @Transactional(readOnly = true)
    public long overdueCount(Set<Long> vesselIds) {
        if (vesselIds.isEmpty()) return 0;
        Long n = named.queryForObject("""
                select count(*) from spare_maintenance_rule
                where vessel_id in (:ids) and active = true
                  and next_due_date is not null and next_due_date < :today
                """, new MapSqlParameterSource("ids", vesselIds)
                        .addValue("today", Date.valueOf(LocalDate.now())), Long.class);
        return n == null ? 0 : n;
    }

    @Override
    @Transactional(readOnly = true)
    public long dueSoonCount(Set<Long> vesselIds, int withinDays) {
        if (vesselIds.isEmpty()) return 0;
        LocalDate today = LocalDate.now();
        Long n = named.queryForObject("""
                select count(*) from spare_maintenance_rule
                where vessel_id in (:ids) and active = true
                  and next_due_date is not null
                  and next_due_date >= :today and next_due_date <= :cutoff
                """, new MapSqlParameterSource("ids", vesselIds)
                        .addValue("today", Date.valueOf(today))
                        .addValue("cutoff", Date.valueOf(today.plusDays(withinDays))), Long.class);
        return n == null ? 0 : n;
    }

    @Override
    @Transactional(readOnly = true)
    public long attentionCount(Set<Long> vesselIds) {
        Map<DueStatus, Long> breakdown = dueBreakdown(vesselIds);
        return breakdown.entrySet().stream()
                .filter(e -> e.getKey().needsAttention())
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, VesselDueCounts> perVessel(Set<Long> vesselIds) {
        Map<Long, VesselDueCounts> result = new HashMap<>();
        if (vesselIds.isEmpty()) return result;

        LocalDate today = LocalDate.now();

        // One grouped query for every vessel, rather than two queries each.
        named.query("""
                select vessel_id,
                       sum(case when next_due_date < :today then 1 else 0 end) as overdue_n,
                       sum(case when next_due_date >= :today and next_due_date <= :cutoff
                                then 1 else 0 end) as due_soon_n,
                       count(*) as total_n
                from spare_maintenance_rule
                where vessel_id in (:ids) and active = true and next_due_date is not null
                group by vessel_id
                """, new MapSqlParameterSource("ids", vesselIds)
                        .addValue("today", Date.valueOf(today))
                        .addValue("cutoff", Date.valueOf(today.plusDays(15))),
                rs -> { result.put(rs.getLong("vessel_id"), new VesselDueCounts(
                        rs.getLong("due_soon_n"), rs.getLong("overdue_n"), rs.getLong("total_n"))); });

        for (Long id : vesselIds) {
            result.putIfAbsent(id, new VesselDueCounts(0, 0, 0));
        }
        return result;
    }

    private static final String DUE_SELECT = """
            select r.spare_id, r.vessel_id, v.name as vessel_name, v.organization_id,
                   s.name as spare_name, s.path, c.code as cat_code,
                   r.next_due_date, r.rule_type
            from spare_maintenance_rule r
            join spare s on s.id = r.spare_id
            join vessel v on v.id = r.vessel_id
            join equipment_category c on c.id = s.equipment_category_id
            """;

    private DueItem mapDueItem(java.sql.ResultSet rs, LocalDate today) throws java.sql.SQLException {
        Date due = rs.getDate("next_due_date");
        LocalDate dueDate = due == null ? null : due.toLocalDate();
        Integer days = dueDate == null ? null : (int) ChronoUnit.DAYS.between(today, dueDate);

        DueStatus status = days == null
                ? DueStatus.NOT_TRACKED
                : engine.classify(days, rs.getLong("organization_id"));

        return new DueItem(
                rs.getLong("spare_id"), rs.getLong("vessel_id"), rs.getString("vessel_name"),
                rs.getString("spare_name"), rs.getString("path"), rs.getString("cat_code"),
                dueDate, days, status, status.colour(), status.shape(),
                rs.getString("rule_type"));
    }
}
