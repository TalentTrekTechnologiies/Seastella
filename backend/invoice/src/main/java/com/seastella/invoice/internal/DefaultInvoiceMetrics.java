package com.seastella.invoice.internal;

import com.seastella.invoice.api.InvoiceMetrics;
import com.seastella.invoice.api.InvoiceStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Invoice aggregates.
 *
 * <p>Callers are responsible for having established that the requesting role
 * may see money at all - this class assumes that check has happened, which is
 * why the Captain and Engineer dashboard services never reach it.
 */
@Service
class DefaultInvoiceMetrics implements InvoiceMetrics {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    DefaultInvoiceMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<InvoiceStatus, Aggregate> summary(Set<Long> vesselIds) {
        Map<InvoiceStatus, Aggregate> result = zeroed();
        if (vesselIds.isEmpty()) return result;

        named.query("""
                select status, count(*) as c, coalesce(sum(amount), 0) as total
                from invoice where vessel_id in (:ids) group by status
                """, new MapSqlParameterSource("ids", vesselIds),
                rs -> { result.put(InvoiceStatus.valueOf(rs.getString("status")),
                        new Aggregate(rs.getLong("c"), rs.getBigDecimal("total"))); });
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<InvoiceStatus, Aggregate> summaryPlatformWide() {
        Map<InvoiceStatus, Aggregate> result = zeroed();
        jdbc.query("""
                select status, count(*) as c, coalesce(sum(amount), 0) as total
                from invoice group by status
                """, rs -> { result.put(InvoiceStatus.valueOf(rs.getString("status")),
                        new Aggregate(rs.getLong("c"), rs.getBigDecimal("total"))); });
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceSummary> pendingAcceptance(Set<Long> vesselIds, int limit) {
        if (vesselIds.isEmpty()) return List.of();

        return named.query(INVOICE_SELECT + """
                where i.vessel_id in (:ids) and i.status = 'RAISED'
                order by i.created_at asc
                limit :lim
                """, new MapSqlParameterSource("ids", vesselIds).addValue("lim", limit),
                (rs, n) -> map(rs));
    }

    /**
     * Accepted invoices whose request has not yet been assigned: the
     * Coordinator's "ready to assign" queue. The {@code assigned_engineer_user_id
     * is null} clause is what makes this a queue of work rather than a list of
     * everything ever accepted.
     */
    @Override
    @Transactional(readOnly = true)
    public List<InvoiceSummary> acceptedAwaitingAssignment(Set<Long> vesselIds, int limit) {
        if (vesselIds.isEmpty()) return List.of();

        return named.query(INVOICE_SELECT + """
                where i.vessel_id in (:ids) and i.status = 'ACCEPTED'
                  and r.assigned_engineer_user_id is null
                  and r.closed_at is null
                order by i.decided_at asc
                limit :lim
                """, new MapSqlParameterSource("ids", vesselIds).addValue("lim", limit),
                (rs, n) -> map(rs));
    }

    @Override
    @Transactional(readOnly = true)
    public long countByStatus(Set<Long> vesselIds, InvoiceStatus status) {
        if (vesselIds.isEmpty()) return 0;
        Long n = named.queryForObject(
                "select count(*) from invoice where vessel_id in (:ids) and status = :st",
                new MapSqlParameterSource("ids", vesselIds).addValue("st", status.name()), Long.class);
        return n == null ? 0 : n;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal totalByStatus(Set<Long> vesselIds, InvoiceStatus status) {
        if (vesselIds.isEmpty()) return BigDecimal.ZERO;
        BigDecimal total = named.queryForObject(
                "select coalesce(sum(amount), 0) from invoice where vessel_id in (:ids) and status = :st",
                new MapSqlParameterSource("ids", vesselIds).addValue("st", status.name()),
                BigDecimal.class);
        return total == null ? BigDecimal.ZERO : total;
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceSummary acceptedFor(Long serviceRequestId) {
        if (serviceRequestId == null) return null;
        List<InvoiceSummary> rows = named.query(INVOICE_SELECT + """
                where i.service_request_id = :id and i.status = 'ACCEPTED'
                """, new MapSqlParameterSource("id", serviceRequestId), (rs, n) -> map(rs));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceSummary> forServiceRequest(Long serviceRequestId) {
        if (serviceRequestId == null) return List.of();
        return named.query(INVOICE_SELECT + """
                where i.service_request_id = :id
                order by i.created_at desc
                """, new MapSqlParameterSource("id", serviceRequestId), (rs, n) -> map(rs));
    }

    private static final String INVOICE_SELECT = """
            select i.id, i.invoice_number, i.service_request_id, r.request_number,
                   i.vessel_id, v.name as vessel_name, i.amount, i.currency, i.description,
                   i.status, i.raised_by_user_id, ru.full_name as raised_by_name,
                   i.decided_by_user_id, du.full_name as decided_by_name, i.decision_note,
                   i.created_at, i.decided_at
            from invoice i
            join service_request r on r.id = i.service_request_id
            join vessel v on v.id = i.vessel_id
            join app_user ru on ru.id = i.raised_by_user_id
            left join app_user du on du.id = i.decided_by_user_id
            """;

    private static InvoiceSummary map(ResultSet rs) throws SQLException {
        InvoiceStatus status = InvoiceStatus.valueOf(rs.getString("status"));
        Object decidedBy = rs.getObject("decided_by_user_id");

        return new InvoiceSummary(
                rs.getLong("id"), rs.getString("invoice_number"),
                rs.getLong("service_request_id"), rs.getString("request_number"),
                rs.getLong("vessel_id"), rs.getString("vessel_name"),
                rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("description"),
                status, status.label(),
                rs.getLong("raised_by_user_id"), rs.getString("raised_by_name"),
                decidedBy == null ? null : ((Number) decidedBy).longValue(),
                rs.getString("decided_by_name"), rs.getString("decision_note"),
                instant(rs, "created_at"), instant(rs, "decided_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Map<InvoiceStatus, Aggregate> zeroed() {
        Map<InvoiceStatus, Aggregate> m = new EnumMap<>(InvoiceStatus.class);
        for (InvoiceStatus s : InvoiceStatus.values()) m.put(s, new Aggregate(0, BigDecimal.ZERO));
        return m;
    }
}
