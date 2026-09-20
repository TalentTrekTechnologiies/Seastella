package com.seastella.fleet.internal;

import com.seastella.fleet.api.Criticality;
import com.seastella.fleet.api.FleetMetrics;
import com.seastella.fleet.api.SpareStatus;
import com.seastella.fleet.api.VesselStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fleet aggregates, computed in SQL.
 *
 * <p>Uses JDBC rather than JPA for the aggregate paths on purpose: these are
 * grouped counts over whole tables, and loading entities to count them is the
 * classic way a dashboard becomes slow. The row-returning methods still project
 * straight into records, so nothing materialises an entity graph either.
 *
 * <p>An empty vessel set short-circuits to empty results. That case is not an
 * edge case: it is what a newly-created Ship Manager with no allocation sees,
 * and {@code IN ()} is a SQL syntax error.
 */
@Service
class DefaultFleetMetrics implements FleetMetrics {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    DefaultFleetMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<VesselStatus, Long> vesselStatusBreakdown(Set<Long> vesselIds) {
        Map<VesselStatus, Long> result = emptyVesselStatuses();
        if (vesselIds.isEmpty()) return result;

        named.query("select status, count(*) as c from vessel where id in (:ids) group by status",
                new MapSqlParameterSource("ids", vesselIds),
                rs -> {
                    result.put(VesselStatus.valueOf(rs.getString("status")), rs.getLong("c"));
                });
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<VesselStatus, Long> vesselStatusBreakdownPlatformWide() {
        Map<VesselStatus, Long> result = emptyVesselStatuses();
        jdbc.query("select status, count(*) as c from vessel group by status", rs -> {
            result.put(VesselStatus.valueOf(rs.getString("status")), rs.getLong("c"));
        });
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public long spareCount(Set<Long> vesselIds) {
        if (vesselIds.isEmpty()) return 0;
        Long n = named.queryForObject("select count(*) from spare where vessel_id in (:ids)",
                new MapSqlParameterSource("ids", vesselIds), Long.class);
        return n == null ? 0 : n;
    }

    @Override
    @Transactional(readOnly = true)
    public long vesselCount(Set<Long> vesselIds) {
        return vesselIds.size();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Criticality, Long> spareCriticalityBreakdown(Set<Long> vesselIds) {
        Map<Criticality, Long> result = new EnumMap<>(Criticality.class);
        for (Criticality c : Criticality.values()) result.put(c, 0L);
        if (vesselIds.isEmpty()) return result;

        named.query("""
                select criticality, count(*) as c from spare
                where vessel_id in (:ids) group by criticality
                """, new MapSqlParameterSource("ids", vesselIds),
                rs -> { result.put(Criticality.valueOf(rs.getString("criticality")), rs.getLong("c")); });
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> spareCategoryBreakdown(Set<Long> vesselIds) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (vesselIds.isEmpty()) return result;

        named.query("""
                select c.code as code, count(*) as n from spare s
                join equipment_category c on c.id = s.equipment_category_id
                where s.vessel_id in (:ids)
                group by c.code, c.display_order
                order by c.display_order
                """, new MapSqlParameterSource("ids", vesselIds),
                rs -> { result.put(rs.getString("code"), rs.getLong("n")); });
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<VesselSummary> vesselSummaries(Set<Long> vesselIds) {
        if (vesselIds.isEmpty()) return List.of();

        // Spare counts join in as an aggregate rather than one query per
        // vessel - this is the obvious N+1 on a fleet dashboard.
        return named.query("""
                select v.id, v.name, v.imo_number, v.vessel_type, v.flag, v.status,
                       v.organization_id, o.name as org_name,
                       coalesce(sc.n, 0) as spare_count
                from vessel v
                join organization o on o.id = v.organization_id
                left join (select vessel_id, count(*) as n from spare group by vessel_id) sc
                       on sc.vessel_id = v.id
                where v.id in (:ids)
                order by v.name
                """, new MapSqlParameterSource("ids", vesselIds), (rs, i) -> new VesselSummary(
                rs.getLong("id"), rs.getString("name"), rs.getString("imo_number"),
                rs.getString("vessel_type"), rs.getString("flag"),
                VesselStatus.valueOf(rs.getString("status")),
                rs.getLong("organization_id"), rs.getString("org_name"),
                rs.getLong("spare_count")));
    }

    @Override
    @Transactional(readOnly = true)
    public VesselSummary vesselSummary(Long vesselId) {
        List<VesselSummary> one = vesselSummaries(Set.of(vesselId));
        return one.isEmpty() ? null : one.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PartShortage> partShortages(Set<Long> vesselIds, int limit) {
        if (vesselIds.isEmpty()) return List.of();

        // The shortage condition is evaluated here, never read from a stored
        // flag that could drift from the quantity it describes.
        return named.query("""
                select p.id, p.vessel_id, v.name as vessel_name, p.name, p.part_number,
                       p.quantity_on_hand, p.minimum_quantity, p.location
                from replacement_part p
                join vessel v on v.id = p.vessel_id
                where p.vessel_id in (:ids) and p.quantity_on_hand < p.minimum_quantity
                order by (p.minimum_quantity - p.quantity_on_hand) desc, v.name
                limit :lim
                """, new MapSqlParameterSource("ids", vesselIds).addValue("lim", limit),
                (rs, i) -> new PartShortage(
                        rs.getLong("id"), rs.getLong("vessel_id"), rs.getString("vessel_name"),
                        rs.getString("name"), rs.getString("part_number"),
                        rs.getInt("quantity_on_hand"), rs.getInt("minimum_quantity"),
                        rs.getString("location")));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PartShortage> parts(Set<Long> vesselIds, int limit) {
        if (vesselIds.isEmpty()) return List.of();

        // The whole inventory, short or not; the report marks what is below minimum.
        return named.query("""
                select p.id, p.vessel_id, v.name as vessel_name, p.name, p.part_number,
                       p.quantity_on_hand, p.minimum_quantity, p.location
                from replacement_part p
                join vessel v on v.id = p.vessel_id
                where p.vessel_id in (:ids)
                order by v.name, p.name
                limit :lim
                """, new MapSqlParameterSource("ids", vesselIds).addValue("lim", limit),
                (rs, i) -> new PartShortage(
                        rs.getLong("id"), rs.getLong("vessel_id"), rs.getString("vessel_name"),
                        rs.getString("name"), rs.getString("part_number"),
                        rs.getInt("quantity_on_hand"), rs.getInt("minimum_quantity"),
                        rs.getString("location")));
    }

    @Override
    @Transactional(readOnly = true)
    public long partShortageCount(Set<Long> vesselIds) {
        if (vesselIds.isEmpty()) return 0;
        Long n = named.queryForObject("""
                select count(*) from replacement_part
                where vessel_id in (:ids) and quantity_on_hand < minimum_quantity
                """, new MapSqlParameterSource("ids", vesselIds), Long.class);
        return n == null ? 0 : n;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SpareNode> spareTree(Long vesselId) {
        if (vesselId == null) return List.of();
        return jdbc.query(SPARE_SELECT + " where s.vessel_id = ? order by s.path",
                (rs, i) -> mapSpare(rs), vesselId);
    }

    @Override
    @Transactional(readOnly = true)
    public SpareNode spare(Long spareId) {
        if (spareId == null) return null;
        List<SpareNode> one = jdbc.query(SPARE_SELECT + " where s.id = ?",
                (rs, i) -> mapSpare(rs), spareId);
        return one.isEmpty() ? null : one.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public long organizationCount() {
        Long n = jdbc.queryForObject("select count(*) from organization", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationSummary> organizationSummaries() {
        return jdbc.query("""
                select o.id, o.code, o.name,
                       coalesce(vc.n, 0) as vessel_count,
                       coalesce(uc.n, 0) as user_count
                from organization o
                left join (select organization_id, count(*) as n from vessel group by organization_id) vc
                       on vc.organization_id = o.id
                left join (select organization_id, count(*) as n from app_user group by organization_id) uc
                       on uc.organization_id = o.id
                order by o.name
                """, (rs, i) -> new OrganizationSummary(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getLong("vessel_count"), rs.getLong("user_count")));
    }

    private static final String SPARE_SELECT = """
            select s.id, s.vessel_id, v.name as vessel_name, s.parent_spare_id, s.path, s.depth,
                   s.name, c.code as cat_code, c.name as cat_name,
                   s.make, s.model, s.serial_number,
                   s.tracks_running_hours, s.running_hours, s.criticality, s.status
            from spare s
            join vessel v on v.id = s.vessel_id
            join equipment_category c on c.id = s.equipment_category_id
            """;

    private static SpareNode mapSpare(java.sql.ResultSet rs) throws java.sql.SQLException {
        Object parent = rs.getObject("parent_spare_id");
        java.math.BigDecimal hours = rs.getBigDecimal("running_hours");
        return new SpareNode(
                rs.getLong("id"), rs.getLong("vessel_id"), rs.getString("vessel_name"),
                parent == null ? null : ((Number) parent).longValue(),
                rs.getString("path"), rs.getInt("depth"), rs.getString("name"),
                rs.getString("cat_code"), rs.getString("cat_name"),
                rs.getString("make"), rs.getString("model"), rs.getString("serial_number"),
                rs.getBoolean("tracks_running_hours"),
                hours == null ? null : hours.toPlainString(),
                Criticality.valueOf(rs.getString("criticality")),
                SpareStatus.valueOf(rs.getString("status")));
    }

    private static Map<VesselStatus, Long> emptyVesselStatuses() {
        Map<VesselStatus, Long> m = new EnumMap<>(VesselStatus.class);
        for (VesselStatus s : VesselStatus.values()) m.put(s, 0L);
        return m;
    }
}
