package com.seastella.fleet.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.ForbiddenException;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeGuard;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.identity.api.UserDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * The service history of one Spare (SoW §6.3, §9.3).
 *
 * <p>Two kinds of row sit here together. The platform writes one whenever a
 * service request completes, carrying the request number. A Technical Head
 * records the rest by hand: work done before this platform existed, or by a
 * contractor who was never on it. A fleet joining the platform arrives with
 * years of that, and an auditor asks for it by item.
 *
 * <p>A recorded row can be removed if it was entered wrongly; a platform row
 * cannot, because it is the service request's own account of itself and
 * deleting it would let the two disagree. Every write is audited either way.
 *
 * <p>The newest date here is what the maintenance engine counts from, so
 * adding a history entry moves the next-due date and re-colours the item —
 * which is the point of entering it.
 */
@RestController
@RequestMapping("/api/v1/spares/{spareId}/service-history")
class ServiceHistoryController {

    private final SpareServiceRecordRepository records;
    private final SpareRepository spares;
    private final ScopeGuard scopeGuard;
    private final ScopeResolver scopes;
    private final UserDirectory users;
    private final AuditService audit;
    private final ServiceDateSync dates;

    ServiceHistoryController(SpareServiceRecordRepository records, SpareRepository spares, ScopeGuard scopeGuard,
                             ScopeResolver scopes, UserDirectory users, AuditService audit,
                             ServiceDateSync dates) {
        this.records = records;
        this.spares = spares;
        this.scopeGuard = scopeGuard;
        this.scopes = scopes;
        this.users = users;
        this.audit = audit;
        this.dates = dates;
    }

    @GetMapping
    @Transactional(readOnly = true)
    ResponseEntity<List<RecordView>> history(@PathVariable Long spareId) {
        Spare spare = spare(spareId);
        List<SpareServiceRecord> found = records.findBySpareIdOrderByServiceDateDescIdDesc(spare.getId());
        Map<Long, UserDirectory.UserRef> people = users.findAll(found.stream()
                .map(SpareServiceRecord::getRecordedByUserId).filter(java.util.Objects::nonNull).toList());
        return ResponseEntity.ok(found.stream().map(r -> view(r, people)).toList());
    }

    /**
     * Records a service that the platform did not see (SoW §9.3).
     *
     * <p>Back-dating is the normal case, not an edge one — this exists so a
     * client can bring their existing history with them. A future date is
     * refused: a service that has not happened is not history.
     */
    @PostMapping
    @Transactional
    ResponseEntity<RecordView> record(@PathVariable Long spareId, @RequestBody RecordBody body) {
        AccessScope actor = mayRecord();
        Spare spare = spare(spareId);
        if (body == null || body.serviceDate() == null) {
            throw new ValidationException("Give the date the service was performed.");
        }
        if (body.serviceDate().isAfter(LocalDate.now(ZoneOffset.UTC))) {
            throw new ValidationException("A service date cannot be in the future.");
        }
        if (spare.getInstallationDate() != null && body.serviceDate().isBefore(spare.getInstallationDate())) {
            throw new ValidationException("That is before the equipment was installed ("
                    + spare.getInstallationDate() + ").");
        }

        SpareServiceRecord saved = records.save(SpareServiceRecord.recorded(
                spare.getId(), spare.getVesselId(), body.serviceDate(),
                required(body.workPerformed(), 2000),
                text(body.partsUsed(), 1000),
                text(body.performedBy(), 200),
                text(body.notes(), 1000),
                actor.userId()));

        dates.applyNewestDate(spare, actor.userId());
        audit.record(entry(actor, AuditAction.SERVICE_DATE_CHANGED, spare)
                .after(AuditJson.of("serviceDate", saved.getServiceDate(), "workPerformed", saved.getWorkPerformed(),
                        "performedBy", saved.getPerformedBy(), "source", "RECORDED"))
                .build());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(saved, Map.of()));
    }

    /** Removes a mistyped entry. Only one that was typed in. */
    @DeleteMapping("/{recordId}")
    @Transactional
    ResponseEntity<Void> remove(@PathVariable Long spareId, @PathVariable Long recordId) {
        AccessScope actor = mayRecord();
        Spare spare = spare(spareId);
        SpareServiceRecord record = records.findById(recordId)
                .orElseThrow(() -> NotFoundException.ofResource("ServiceRecord", recordId));
        if (!record.getSpareId().equals(spare.getId())) {
            throw NotFoundException.ofResource("ServiceRecord", recordId);
        }
        if (!record.isRecorded()) {
            throw new WorkflowException("This entry came from service request " + record.getRequestNumber()
                    + " and is part of that request's record. It cannot be removed here.");
        }

        records.delete(record);
        dates.applyNewestDate(spare, actor.userId());
        audit.record(entry(actor, AuditAction.SERVICE_DATE_CHANGED, spare)
                .before(AuditJson.of("serviceDate", record.getServiceDate(),
                        "workPerformed", record.getWorkPerformed(), "source", "RECORDED"))
                .after(AuditJson.of("removed", true))
                .build());
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------- internals

    private AccessScope mayRecord() {
        AccessScope actor = scopes.currentScope();
        // Master data is the Technical Head's, under the Platform Admin.
        if (actor.role() != Role.TECHNICAL_HEAD && actor.role() != Role.PLATFORM_ADMIN) {
            throw ForbiddenException.ofAction("record service history");
        }
        return actor;
    }

    private Spare spare(Long spareId) {
        Spare spare = spares.findById(spareId)
                .orElseThrow(() -> NotFoundException.ofResource("Spare", spareId));
        scopeGuard.assertVessel(spare.getVesselId());
        return spare;
    }

    private RecordView view(SpareServiceRecord r, Map<Long, UserDirectory.UserRef> people) {
        UserDirectory.UserRef who = r.getRecordedByUserId() == null ? null : people.get(r.getRecordedByUserId());
        return new RecordView(r.getId(), r.getServiceDate(), r.getSource(), r.getWorkPerformed(), r.getPartsUsed(),
                r.getPerformedBy(), r.getServiceRequestId(), r.getRequestNumber(),
                who == null ? null : who.fullName(), r.getNotes(), r.isRecorded());
    }

    private static AuditEntry.Builder entry(AccessScope actor, String action, Spare spare) {
        return AuditEntry.builder()
                .actor(actor.userId(), actor.role().name())
                .action(action)
                .entity("Spare", spare.getId())
                .scope(null, spare.getVesselId());
    }

    private static String required(String value, int max) {
        String t = value == null ? "" : value.trim();
        if (t.isEmpty()) throw new ValidationException("Say what work was performed.");
        if (t.length() > max) throw new ValidationException("Keep this under " + max + " characters.");
        return t;
    }

    private static String text(String value, int max) {
        if (value == null) return null;
        String t = value.trim();
        if (t.isEmpty()) return null;
        if (t.length() > max) throw new ValidationException("Keep this under " + max + " characters.");
        return t;
    }

    record RecordBody(LocalDate serviceDate, String workPerformed, String partsUsed, String performedBy,
                      String notes) {}

    record RecordView(Long id, LocalDate serviceDate, String source, String workPerformed, String partsUsed,
                      String performedBy, Long serviceRequestId, String requestNumber, String recordedBy,
                      String notes, boolean removable) {}
}
