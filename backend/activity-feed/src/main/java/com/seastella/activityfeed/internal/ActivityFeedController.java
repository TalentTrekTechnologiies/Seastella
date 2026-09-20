package com.seastella.activityfeed.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Platform Admin's platform-wide activity feed (SoW s8.5, FEE-01 to FEE-06).
 *
 * <p>SoW s12: every approval, rejection, upload and service update "is logged
 * with user, timestamp and old/new values, and surfaced platform-wide to the
 * Platform Admin via the activity feed". The feed is therefore a readable view
 * of the audit trail - one record, told once - rather than a second log that
 * could disagree with it. Sign-ins and each individual troubleshooting answer
 * stay in the audit trail but out of the feed, where they would bury the events
 * the SoW names.
 *
 * <p>Platform Admin only; it spans every organization and vessel. An open feed
 * is pushed: the server announces each new entry over SSE and the client asks
 * for what is newer than the last id it holds (FEE-04).
 */
@RestController
@RequestMapping("/api/v1/activity")
class ActivityFeedController {

    enum Category { REQUESTS, INVOICES, MAINTENANCE, SETUP }

    private static final Map<Category, Set<String>> ACTIONS = Map.of(
            Category.REQUESTS, Set.of("REQUEST_RAISED", "REQUEST_TRANSITIONED", "REQUEST_APPROVED", "REQUEST_REJECTED",
                    "CLARIFICATION_REQUESTED", "ESCALATED_TO_LIVE_AGENT", "REQUEST_CLOSED_NO_COST", "ENGINEER_ASSIGNED",
                    "COMPLETION_REPORTED", "REQUEST_COMPLETED", "TROUBLESHOOTING_COMPLETED"),
            Category.INVOICES, Set.of("INVOICE_RAISED", "INVOICE_ACCEPTED", "INVOICE_REJECTED", "INVOICE_QUERIED"),
            Category.MAINTENANCE, Set.of("MAINTENANCE_STATUS_CHANGED", "RUNNING_HOURS_RECORDED", "SPARE_UPDATED"),
            Category.SETUP, Set.of("ORGANIZATION_CREATED", "USER_CREATED", "VESSEL_CREATED", "VESSEL_ASSIGNED",
                    "VESSEL_UNASSIGNED", "USER_STATUS_CHANGED", "PASSWORD_RESET", "ORGANIZATION_ASSIGNED",
                    "CONFIGURATION_CHANGED", "INVITATION_ACCEPTED", "PROBLEM_TYPE_CREATED", "PROBLEM_TYPE_UPDATED",
                    "CHECKS_PUBLISHED", "CHECKS_RETIRED"));

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ActivityStream stream;

    ActivityFeedController(JdbcTemplate jdbc, ObjectMapper json, ActivityStream stream) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.json = json;
        this.stream = stream;
    }

    /**
     * The push channel (FEE-04). Each frame names an audit entry that has just
     * been written; the client then reads it through the feed endpoint with
     * {@code after}, so one query shape serves both paths.
     */
    @GetMapping("/stream")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    SseEmitter stream() {
        return stream.subscribe();
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Transactional(readOnly = true)
    ResponseEntity<Feed> feed(@RequestParam(required = false) Long organizationId,
                              @RequestParam(required = false) Long vesselId,
                              @RequestParam(required = false) Category category,
                              @RequestParam(required = false) Long before,
                              @RequestParam(required = false) Long after,
                              @RequestParam(defaultValue = "50") int limit) {
        int size = Math.min(Math.max(limit, 1), 200);
        StringBuilder sql = new StringBuilder("""
                select a.id, a.action, a.entity_type, a.entity_id, a.occurred_at, a.after_value, a.actor_role, a.actor_user_id,
                       u.full_name as actor_name, v.id as v_id, v.name as vessel_name,
                       o.id as org_id, o.name as org_name,
                       sr.id as sr_id, sr.request_number, sr.title as sr_title,
                       inv.invoice_number, inv.service_request_id as inv_sr_id, isr.request_number as inv_sr_number,
                       sp.name as spare_name, tu.full_name as target_name, tu.role as target_role,
                       eo.name as entity_org_name, ev.name as entity_vessel_name
                from audit_entry a
                left join app_user u on u.id = a.actor_user_id
                left join vessel v on v.id = a.vessel_id
                left join organization o on o.id = coalesce(a.organization_id, v.organization_id)
                left join service_request sr on a.entity_type = 'ServiceRequest' and sr.id = a.entity_id
                left join invoice inv on a.entity_type = 'Invoice' and inv.id = a.entity_id
                left join service_request isr on isr.id = inv.service_request_id
                left join spare sp on a.entity_type = 'Spare' and sp.id = a.entity_id
                left join app_user tu on a.entity_type = 'AppUser' and tu.id = a.entity_id
                left join organization eo on a.entity_type = 'Organization' and eo.id = a.entity_id
                left join vessel ev on a.entity_type = 'Vessel' and ev.id = a.entity_id
                where a.action in (:actions)
                  and not (a.action = 'REQUEST_TRANSITIONED' and a.after_value like '%INVOICE%')
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("actions", category == null
                        ? ACTIONS.values().stream().flatMap(Set::stream).toList()
                        : List.copyOf(ACTIONS.get(category)))
                .addValue("lim", size);
        if (organizationId != null) {
            sql.append(" and coalesce(a.organization_id, v.organization_id) = :org");
            params.addValue("org", organizationId);
        }
        if (vesselId != null) {
            sql.append(" and a.vessel_id = :vessel");
            params.addValue("vessel", vesselId);
        }
        if (before != null) {
            sql.append(" and a.id < :before");
            params.addValue("before", before);
        }
        // Only what the client has not seen, for a stream-driven top-up (FEE-04).
        if (after != null) {
            sql.append(" and a.id > :after");
            params.addValue("after", after);
        }
        sql.append(" order by a.id desc limit :lim");

        List<Item> items = jdbc.query(sql.toString(), params, (rs, i) -> toItem(rs));
        List<Option> organizations = jdbc.query("select id, name from organization order by name",
                (rs, i) -> new Option(rs.getLong("id"), rs.getString("name")));
        Long next = items.size() == size ? items.get(items.size() - 1).id() : null;
        return ResponseEntity.ok(new Feed(items, next, organizations));
    }

    private Item toItem(ResultSet rs) throws SQLException {
        String action = rs.getString("action");
        JsonNode after = parse(rs.getString("after_value"));
        String requestNumber = firstNonNull(rs.getString("request_number"), rs.getString("inv_sr_number"));
        Long requestId = rs.getObject("sr_id") != null ? Long.valueOf(rs.getLong("sr_id"))
                : rs.getObject("inv_sr_id") != null ? Long.valueOf(rs.getLong("inv_sr_id")) : null;
        String actor = rs.getString("actor_name");
        String vessel = firstNonNull(rs.getString("vessel_name"), rs.getString("entity_vessel_name"));
        String target = rs.getString("target_name");
        Timestamp at = rs.getTimestamp("occurred_at");

        return new Item(
                rs.getLong("id"),
                at == null ? null : at.toInstant(),
                categoryOf(action).name(),
                action,
                actor == null ? "SeaStella" : actor,
                actor == null ? null : roleLabel(rs.getString("actor_role")),
                summary(action, after, requestNumber, rs.getString("sr_title"), rs.getString("invoice_number"),
                        rs.getString("spare_name"), target, rs.getString("target_role"), vessel,
                        rs.getString("entity_org_name"),
                        "AppUser".equals(rs.getString("entity_type")) && rs.getObject("actor_user_id") != null
                                && rs.getLong("actor_user_id") == rs.getLong("entity_id")),
                firstNonNull(rs.getString("org_name"), rs.getString("entity_org_name")),
                vessel,
                requestId);
    }

    /** One sentence per event, in the words the SoW uses. Reads after the actor's name. */
    private static String summary(String action, JsonNode after, String sr, String srTitle, String invoice,
                                  String spare, String target, String targetRole, String vessel, String org,
                                  boolean aboutThemselves) {
        String on = vessel == null ? "" : " on " + vessel;
        return switch (action) {
            case "REQUEST_RAISED" -> "raised " + sr + on + (srTitle == null ? "" : ": “" + srTitle + "”");
            case "REQUEST_TRANSITIONED" -> switch (text(after, "action")) {
                case "START_TROUBLESHOOTING" -> "started guided checks on " + sr;
                case "SUBMIT_FOR_APPROVAL" -> "submitted " + sr + " for approval";
                case "RESUBMIT" -> "resubmitted " + sr + " with clarification";
                case "START_WORK" -> "started work on " + sr;
                default -> "moved " + sr + " to " + text(after, "status").toLowerCase(Locale.ROOT).replace('_', ' ');
            };
            case "REQUEST_APPROVED" -> "approved " + sr;
            case "REQUEST_REJECTED" -> "rejected " + sr + reason(after);
            case "CLARIFICATION_REQUESTED" -> "asked for clarification on " + sr + reason(after);
            case "ESCALATED_TO_LIVE_AGENT" -> "escalated " + sr + " to a live agent";
            case "REQUEST_CLOSED_NO_COST" -> "closed " + sr + " without cost";
            case "ENGINEER_ASSIGNED" -> "assigned a Service Engineer to " + sr;
            case "COMPLETION_REPORTED" -> "reported the work on " + sr + " complete";
            case "REQUEST_COMPLETED" -> "marked " + sr + " completed";
            case "TROUBLESHOOTING_COMPLETED" -> "finished guided checks on " + sr + ": " + outcome(text(after, "outcome"));
            case "INVOICE_RAISED" -> "raised invoice " + invoice + " for " + sr + money(after);
            case "INVOICE_ACCEPTED" -> "accepted invoice " + invoice + " for " + sr;
            case "INVOICE_REJECTED" -> "rejected invoice " + invoice + " for " + sr + note(after);
            case "INVOICE_QUERIED" -> "queried invoice " + invoice + " for " + sr + note(after);
            case "MAINTENANCE_STATUS_CHANGED" -> {
                int n = after == null ? 0 : after.path("spares").asInt();
                yield (n == 1 ? text(after, "names") + " is now " : n + " spares are now at worst ")
                        + outcome(text(after, "worst")).toLowerCase(Locale.ROOT) + on;
            }
            case "RUNNING_HOURS_RECORDED" -> "recorded " + text(after, "runningHours") + " running hours on "
                    + (spare == null ? "a spare" : spare) + on;
            case "SPARE_UPDATED" -> "updated the details of " + (spare == null ? "a spare" : spare) + on;
            case "ORGANIZATION_CREATED" -> "created organization " + firstNonNull(org, text(after, "name"));
            case "USER_CREATED" -> "created a " + roleLabel(targetRole) + " account for " + target;
            case "VESSEL_CREATED" -> "added vessel " + firstNonNull(vessel, text(after, "name"))
                    + " with " + text(after, "standardFitSpares") + " spares";
            case "VESSEL_ASSIGNED" -> "assigned " + target + " (" + roleLabel(targetRole) + ")" + (vessel == null ? "" : " to " + vessel);
            case "VESSEL_UNASSIGNED" -> "released " + target + (vessel == null ? "" : " from " + vessel);
            case "USER_STATUS_CHANGED" -> ("SUSPENDED".equals(text(after, "status")) ? "suspended " : "reactivated ") + target;
            case "PASSWORD_RESET" -> aboutThemselves ? "asked for a password reset link" : "sent a password reset link to " + target;
            case "INVITATION_ACCEPTED" -> "accepted their invitation and activated the account";
            case "PROBLEM_TYPE_CREATED" -> "added the problem type “" + text(after, "label") + "” for " + text(after, "category");
            case "PROBLEM_TYPE_UPDATED" -> after != null && after.has("order") ? "reordered the problem types for a piece of equipment"
                    : (after != null && !after.path("active").asBoolean(true) ? "retired the problem type “" : "updated the problem type “")
                            + text(after, "label") + "”";
            case "CHECKS_PUBLISHED" -> "published guided checks “" + text(after, "name") + "” (version " + text(after, "flowVersion") + ")";
            case "CHECKS_RETIRED" -> "withdrew guided checks “" + text(after, "name") + "”";
            case "ORGANIZATION_ASSIGNED" -> "gave " + target + " service access to an organization";
            case "CONFIGURATION_CHANGED" -> "changed an alert rule (" + text(after, "eventType") + " → "
                    + roleLabel(text(after, "recipientRole")) + ")";
            default -> action.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private static Category categoryOf(String action) {
        return ACTIONS.entrySet().stream().filter(e -> e.getValue().contains(action))
                .map(Map.Entry::getKey).findFirst().orElse(Category.REQUESTS);
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node == null || node.path(field).isMissingNode() || node.path(field).isNull() ? "" : node.path(field).asText();
    }

    private static String reason(JsonNode after) {
        String r = text(after, "reason");
        return r.isEmpty() ? "" : ": “" + r + "”";
    }

    private static String note(JsonNode after) {
        String n = text(after, "note");
        return n.isEmpty() ? "" : ": “" + n + "”";
    }

    private static String money(JsonNode after) {
        String amount = text(after, "amount");
        if (amount.isEmpty()) return "";
        try {
            return " (" + text(after, "currency") + " " + String.format(Locale.ENGLISH, "%,.2f", new BigDecimal(amount)) + ")";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static String outcome(String code) {
        return switch (code) {
            case "RESOLVED" -> "resolved";
            case "TEMPORARY_FIX" -> "temporary fix";
            case "UNRESOLVED" -> "not resolved";
            case "APPROACHING" -> "Approaching";
            case "URGENT" -> "Urgent";
            case "DUE" -> "Due";
            case "OVERDUE" -> "Overdue";
            default -> code.toLowerCase(Locale.ROOT);
        };
    }

    private static String roleLabel(String role) {
        if (role == null) return "";
        return switch (role) {
            case "PLATFORM_ADMIN" -> "Platform Admin";
            case "TECHNICAL_HEAD" -> "Technical Head";
            case "SHIP_MANAGER" -> "Ship Manager";
            case "CAPTAIN" -> "Captain";
            case "SERVICE_COORDINATOR" -> "Service Coordinator";
            case "SERVICE_ENGINEER" -> "Service Engineer";
            default -> role;
        };
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    record Feed(List<Item> items, Long nextBefore, List<Option> organizations) {}

    record Item(Long id, Instant occurredAt, String category, String action, String actorName, String actorRole,
                String summary, String organizationName, String vesselName, Long serviceRequestId) {}

    record Option(Long id, String name) {}
}
