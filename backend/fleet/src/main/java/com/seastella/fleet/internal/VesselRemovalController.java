package com.seastella.fleet.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.ForbiddenException;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeGuard;
import com.seastella.identity.api.ScopeResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Deleting a vessel, and everything that belongs to it.
 *
 * <p>Everything goes: its equipment and critical spares, their service
 * history, running hours and maintenance schedules, its documents and their
 * files, its service requests with their checks, chats, completion reports and
 * invoices, its notifications and import rows, and who was assigned to it. The
 * audit trail is the one thing kept - it cannot be deleted, and it is where the
 * deletion itself is recorded.
 *
 * <p>Because nothing can bring it back, the caller first reads what would be
 * removed, and the delete must be confirmed with the vessel's name.
 *
 * <p>The other modules keep vessel ids without foreign keys, so their rows are
 * removed here by vessel, every statement a fixed text with bound values, in
 * one transaction: a vessel is gone entirely or not at all.
 */
@RestController
@RequestMapping("/api/v1/vessels/{vesselId}")
@PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TECHNICAL_HEAD')")
class VesselRemovalController {

    /** Child rows first, parents after; each removes one vessel's rows. */
    private static final List<String> DELETES = List.of(
            // Troubleshooting and live chat, under their requests.
            "delete from troubleshooting_response where session_id in "
                    + "(select id from troubleshooting_session where vessel_id = :v)",
            "delete from troubleshooting_session where vessel_id = :v",
            "delete from conversation_read where conversation_id in (select id from conversation where vessel_id = :v)",
            "delete from conversation_message where conversation_id in "
                    + "(select id from conversation where vessel_id = :v)",
            "delete from conversation where vessel_id = :v",
            // Service requests and their paperwork.
            "delete from service_request_transition where vessel_id = :v",
            "delete from completion_report where vessel_id = :v",
            "update invoice set supersedes_invoice_id = null where vessel_id = :v",
            "delete from invoice where vessel_id = :v",
            "delete from service_request where vessel_id = :v",
            // Documents; their files are removed once this commits.
            "delete from certificate_alert_state where document_id in (select id from document where vessel_id = :v)",
            "update document set superseded_by_id = null where vessel_id = :v",
            "delete from document where vessel_id = :v",
            // Notifications about it.
            "delete from notification_delivery where notification_id in "
                    + "(select id from notification where vessel_id = :v)",
            "delete from notification where vessel_id = :v",
            // Maintenance, history and stock.
            "delete from spare_due_state where vessel_id = :v",
            "delete from spare_maintenance_rule where vessel_id = :v",
            "delete from running_hour_reading where vessel_id = :v",
            "delete from spare_service_record where vessel_id = :v",
            "delete from replacement_part where vessel_id = :v",
            // Import rows that named it; an upload left with no rows goes with them.
            "delete from import_row where vessel_id = :v",
            "delete from import_batch where not exists (select 1 from import_row r where r.batch_id = import_batch.id) "
                    + "and row_count > 0",
            // Who was assigned to it.
            "delete from user_vessel_assignment where vessel_id = :v",
            // The equipment tree, then the vessel.
            "update spare set parent_spare_id = null where vessel_id = :v",
            "delete from spare where vessel_id = :v",
            "delete from vessel where id = :v");

    private final NamedParameterJdbcTemplate jdbc;
    private final VesselRepository vessels;
    private final ScopeResolver scopes;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;
    private final DocumentStorage storage;

    VesselRemovalController(JdbcTemplate jdbc, VesselRepository vessels, ScopeResolver scopes, ScopeGuard scopeGuard,
                            AuditService audit, DocumentStorage storage) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.vessels = vessels;
        this.scopes = scopes;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
        this.storage = storage;
    }

    /** What deleting this vessel would remove - read before confirming. */
    @GetMapping("/removal")
    @Transactional(readOnly = true)
    ResponseEntity<Impact> impact(@PathVariable Long vesselId) {
        Vessel vessel = target(vesselId);
        return ResponseEntity.ok(impactOf(vessel));
    }

    @DeleteMapping
    @Transactional
    ResponseEntity<Impact> delete(@PathVariable Long vesselId, @RequestParam(required = false) String confirm) {
        AccessScope actor = scopes.currentScope();
        Vessel vessel = target(vesselId);
        if (confirm == null || !confirm.trim().equalsIgnoreCase(vessel.getName().trim())) {
            throw new ValidationException("Type the vessel's name, " + vessel.getName() + ", to confirm.");
        }
        Impact removed = impactOf(vessel);
        MapSqlParameterSource v = new MapSqlParameterSource("v", vesselId);
        List<String> files = jdbc.queryForList("select storage_key from document where vessel_id = :v", v, String.class);

        for (String statement : DELETES) jdbc.update(statement, v);

        // Files are not transactional: remove them only once the rows are gone for good.
        if (!files.isEmpty() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    files.forEach(storage::deleteQuietly);
                }
            });
        }

        audit.record(AuditEntry.builder()
                .actor(actor.userId(), actor.role().name())
                .action(AuditAction.VESSEL_DELETED)
                .entity("Vessel", vesselId)
                .scope(vessel.getOrganizationId(), null)
                .before(AuditJson.of("name", vessel.getName(), "imoNumber", vessel.getImoNumber(),
                        "equipment", removed.equipment(), "criticalSpares", removed.criticalSpares(),
                        "serviceHistory", removed.serviceHistory(), "serviceRequests", removed.serviceRequests(),
                        "invoices", removed.invoices(), "documents", removed.documents()))
                .after(AuditJson.of("deleted", true))
                .build());
        return ResponseEntity.ok(removed);
    }

    private Vessel target(Long vesselId) {
        AccessScope actor = scopes.currentScope();
        if (actor.role() != Role.PLATFORM_ADMIN && actor.role() != Role.TECHNICAL_HEAD) {
            throw ForbiddenException.ofAction("delete a vessel");
        }
        scopeGuard.assertVessel(vesselId);
        return vessels.findById(vesselId).orElseThrow(() -> NotFoundException.ofResource("Vessel", vesselId));
    }

    private Impact impactOf(Vessel vessel) {
        MapSqlParameterSource v = new MapSqlParameterSource("v", vessel.getId());
        return new Impact(vessel.getId(), vessel.getName(), vessel.getImoNumber(),
                count("select count(*) from spare where vessel_id = :v", v),
                count("select count(*) from replacement_part where vessel_id = :v", v),
                count("select count(*) from spare_service_record where vessel_id = :v", v),
                count("select count(*) from service_request where vessel_id = :v", v),
                count("select count(*) from invoice where vessel_id = :v", v),
                count("select count(*) from document where vessel_id = :v", v),
                count("select count(*) from user_vessel_assignment where vessel_id = :v", v));
    }

    private int count(String sql, MapSqlParameterSource params) {
        Integer n = jdbc.queryForObject(sql, params, Integer.class);
        return n == null ? 0 : n;
    }

    record Impact(Long vesselId, String name, String imoNumber, int equipment, int criticalSpares,
                  int serviceHistory, int serviceRequests, int invoices, int documents, int assignedPeople) {}
}
