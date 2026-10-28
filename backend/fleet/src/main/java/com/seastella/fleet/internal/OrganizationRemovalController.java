package com.seastella.fleet.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.ScopeResolver;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Deleting a client organization: every vessel it has, with everything on
 * them, and the organization's own people - its Technical Heads, Ship Managers
 * and Captains. Asked for so a test organization can be cleared off the live
 * platform.
 *
 * <p>Thawe Marine's own people stay. A Service Coordinator who served the
 * organization simply stops serving it; engineers belong to no client.
 *
 * <p>Platform Administrator only. As with a vessel, the caller first reads what
 * would go, and confirms with the organization's name. One transaction: the
 * organization is gone entirely or not at all. The audit trail is kept, and
 * records the deletion.
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class OrganizationRemovalController {

    /** After its vessels are gone: the organization's people, its settings, then itself. */
    private static final List<String> DELETES = List.of(
            // Alerts to its people, and any about the organization itself.
            "delete from notification_delivery where notification_id in (select id from notification "
                    + "where organization_id = :o or recipient_user_id in (select id from app_user where organization_id = :o))",
            "delete from notification where organization_id = :o "
                    + "or recipient_user_id in (select id from app_user where organization_id = :o)",
            // Their sign-in sessions and links.
            "delete from refresh_token where user_id in (select id from app_user where organization_id = :o)",
            "delete from user_token where user_id in (select id from app_user where organization_id = :o)",
            "delete from user_token where created_by_user_id in (select id from app_user where organization_id = :o)",
            // Uploads they made that named no remaining vessel.
            "delete from import_row where batch_id in (select id from import_batch where uploaded_by_user_id in "
                    + "(select id from app_user where organization_id = :o))",
            "delete from import_batch where uploaded_by_user_id in (select id from app_user where organization_id = :o) "
                    + "or previewed_by_user_id in (select id from app_user where organization_id = :o) "
                    + "or committed_by_user_id in (select id from app_user where organization_id = :o)",
            // Assignments: theirs, and Coordinators serving the organization.
            "delete from user_vessel_assignment where user_id in (select id from app_user where organization_id = :o) "
                    + "or assigned_by_user_id in (select id from app_user where organization_id = :o)",
            "delete from user_organization_assignment where organization_id = :o "
                    + "or user_id in (select id from app_user where organization_id = :o) "
                    + "or assigned_by_user_id in (select id from app_user where organization_id = :o)",
            // Its own maintenance bands.
            "delete from maintenance_threshold where organization_id = :o",
            // Its people, then the organization.
            "delete from app_user where organization_id = :o",
            "delete from organization where id = :o");

    private final NamedParameterJdbcTemplate jdbc;
    private final OrganizationRepository organizations;
    private final ScopeResolver scopes;
    private final AuditService audit;
    private final DocumentStorage storage;

    OrganizationRemovalController(JdbcTemplate jdbc, OrganizationRepository organizations, ScopeResolver scopes,
                                  AuditService audit, DocumentStorage storage) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.organizations = organizations;
        this.scopes = scopes;
        this.audit = audit;
        this.storage = storage;
    }

    /** What deleting this organization would remove - read before confirming. */
    @GetMapping("/removal")
    @Transactional(readOnly = true)
    ResponseEntity<Impact> impact(@PathVariable Long organizationId) {
        return ResponseEntity.ok(impactOf(target(organizationId)));
    }

    @DeleteMapping
    @Transactional
    ResponseEntity<Impact> delete(@PathVariable Long organizationId, @RequestParam(required = false) String confirm) {
        AccessScope actor = scopes.currentScope();
        Organization org = target(organizationId);
        if (confirm == null || !confirm.trim().equalsIgnoreCase(org.getName().trim())) {
            throw new ValidationException("Type the organization's name, " + org.getName() + ", to confirm.");
        }
        Impact removed = impactOf(org);
        MapSqlParameterSource o = new MapSqlParameterSource("o", organizationId);

        List<String> files = new ArrayList<>();
        try {
            for (Long vesselId : jdbc.queryForList("select id from vessel where organization_id = :o", o, Long.class)) {
                files.addAll(VesselRemovalController.removeRows(jdbc, vesselId));
            }
            for (String statement : DELETES) jdbc.update(statement, o);
        } catch (DataIntegrityViolationException e) {
            // Something outside the organization still points at one of its people.
            throw new WorkflowException("This organization's people still have records on another organization's "
                    + "vessels, so it cannot be deleted. Nothing was changed.");
        }
        VesselRemovalController.deleteFilesAfterCommit(storage, files);

        audit.record(AuditEntry.builder()
                .actor(actor.userId(), actor.role().name())
                .action(AuditAction.ORGANIZATION_DELETED)
                .entity("Organization", organizationId)
                .before(AuditJson.of("name", org.getName(), "code", org.getCode(), "vessels", removed.vessels(),
                        "people", removed.people(), "serviceRequests", removed.serviceRequests(),
                        "invoices", removed.invoices(), "documents", removed.documents(),
                        "vesselNames", String.join(", ", removed.vesselNames())))
                .after(AuditJson.of("deleted", true))
                .build());
        return ResponseEntity.ok(removed);
    }

    private Organization target(Long organizationId) {
        return organizations.findById(organizationId)
                .orElseThrow(() -> NotFoundException.ofResource("Organization", organizationId));
    }

    private Impact impactOf(Organization org) {
        MapSqlParameterSource o = new MapSqlParameterSource("o", org.getId());
        return new Impact(org.getId(), org.getName(), org.getCode(),
                count("select count(*) from vessel where organization_id = :o", o),
                jdbc.queryForList("select name from vessel where organization_id = :o order by name", o, String.class),
                count("select count(*) from app_user where organization_id = :o", o),
                count("select count(*) from spare where vessel_id in (select id from vessel where organization_id = :o)", o),
                count("select count(*) from service_request where vessel_id in "
                        + "(select id from vessel where organization_id = :o)", o),
                count("select count(*) from invoice where vessel_id in (select id from vessel where organization_id = :o)", o),
                count("select count(*) from document where vessel_id in (select id from vessel where organization_id = :o)", o),
                count("select count(*) from user_organization_assignment where organization_id = :o", o));
    }

    private int count(String sql, MapSqlParameterSource params) {
        Integer n = jdbc.queryForObject(sql, params, Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * @param coordinators Service Coordinators who serve it; they stay, and stop serving it
     */
    record Impact(Long organizationId, String name, String code, int vessels, List<String> vesselNames, int people,
                  int equipment, int serviceRequests, int invoices, int documents, int coordinators) {}
}
