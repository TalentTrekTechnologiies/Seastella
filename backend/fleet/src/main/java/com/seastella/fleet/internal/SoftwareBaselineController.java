package com.seastella.fleet.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.ScopeResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The software baseline master list: the latest release for each equipment
 * model (SoW s9.3).
 *
 * <p>Most of this list arrives as a spreadsheet - see the import module's
 * upload - and this is where it is read back, corrected and kept. A row
 * corrected here is marked RECORDED, which is what stops the next upload of a
 * stale sheet from undoing the correction.
 *
 * <p>Readable by anyone who can see a fleet, because the comparison it drives
 * appears on every vessel's equipment list. Writable by the Platform Admin
 * only: one wrong version here reports a whole fleet as out of date.
 */
@RestController
@RequestMapping("/api/v1/software-baselines")
class SoftwareBaselineController {

    private final SoftwareBaselineRepository baselines;
    private final AuditService audit;
    private final ScopeResolver scopes;

    SoftwareBaselineController(SoftwareBaselineRepository baselines, AuditService audit, ScopeResolver scopes) {
        this.baselines = baselines;
        this.audit = audit;
        this.scopes = scopes;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    ResponseEntity<List<BaselineView>> list() {
        return ResponseEntity.ok(baselines.findAllByOrderByMakeAscModelAsc().stream()
                .map(BaselineView::of)
                .toList());
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Transactional
    ResponseEntity<BaselineView> create(@RequestBody BaselineForm form) {
        String make = text(form.make(), 120, "Make");
        String model = text(form.model(), 120, "Model");
        String latest = text(form.latestVersion(), 64, "Latest version");

        String key = SoftwareMatchKey.of(make, model);
        if (key == null) {
            throw new ValidationException("A baseline needs both a make and a model.");
        }
        baselines.findByMatchKey(key).ifPresent(existing -> {
            throw new ValidationException(
                    "There is already a baseline for " + existing.getMake() + " " + existing.getModel() + ".");
        });

        SoftwareBaseline saved = baselines.save(new SoftwareBaseline(
                make, model, trimToNull(form.equipmentName()), latest, SoftwareBaseline.Source.RECORDED));
        saved.setNotes(trimToNull(form.notes()));

        auditChange(AuditAction.SOFTWARE_BASELINE_CHANGED, saved.getId(), null, saved);
        return ResponseEntity.ok(BaselineView.of(saved));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Transactional
    ResponseEntity<BaselineView> update(@PathVariable Long id, @RequestBody BaselineForm form) {
        SoftwareBaseline baseline = baselines.findById(id)
                .orElseThrow(() -> NotFoundException.ofResource("Software baseline", id));

        String was = baseline.getLatestVersion();
        String make = text(form.make(), 120, "Make");
        String model = text(form.model(), 120, "Model");
        String latest = text(form.latestVersion(), 64, "Latest version");

        String key = SoftwareMatchKey.of(make, model);
        if (key == null) {
            throw new ValidationException("A baseline needs both a make and a model.");
        }
        baselines.findByMatchKey(key).ifPresent(other -> {
            if (!other.getId().equals(id)) {
                throw new ValidationException(
                        "There is already a baseline for " + other.getMake() + " " + other.getModel() + ".");
            }
        });

        baseline.setMakeAndModel(make, model);
        baseline.setEquipmentName(trimToNull(form.equipmentName()));
        baseline.setLatestVersion(latest);
        baseline.setNotes(trimToNull(form.notes()));
        // Edited by hand, so the next sheet upload leaves it alone.
        baseline.setSource(SoftwareBaseline.Source.RECORDED);

        auditChange(AuditAction.SOFTWARE_BASELINE_CHANGED, id, was, baseline);
        return ResponseEntity.ok(BaselineView.of(baseline));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Transactional
    ResponseEntity<Void> delete(@PathVariable Long id) {
        SoftwareBaseline baseline = baselines.findById(id)
                .orElseThrow(() -> NotFoundException.ofResource("Software baseline", id));

        AccessScope scope = scopes.currentScope();
        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role().name())
                .action(AuditAction.SOFTWARE_BASELINE_DELETED)
                .entity("SoftwareBaseline", id)
                .before(AuditJson.of("make", baseline.getMake(), "model", baseline.getModel(),
                        "latestVersion", baseline.getLatestVersion()))
                .build());

        baselines.delete(baseline);
        return ResponseEntity.noContent().build();
    }

    private void auditChange(String action, Long id, String versionBefore, SoftwareBaseline after) {
        AccessScope scope = scopes.currentScope();
        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role().name())
                .action(action)
                .entity("SoftwareBaseline", id)
                .before(versionBefore == null ? null : AuditJson.of("latestVersion", versionBefore))
                .after(AuditJson.of("make", after.getMake(), "model", after.getModel(),
                        "latestVersion", after.getLatestVersion()))
                .build());
    }

    private static String text(String value, int max, String what) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) throw new ValidationException(what + " is required.");
        if (trimmed.length() > max) {
            throw new ValidationException(what + " cannot be longer than " + max + " characters.");
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record BaselineForm(String make, String model, String equipmentName, String latestVersion, String notes) {}

    record BaselineView(Long id, String make, String model, String equipmentName,
                        String latestVersion, String source, String notes) {

        static BaselineView of(SoftwareBaseline b) {
            return new BaselineView(b.getId(), b.getMake(), b.getModel(), b.getEquipmentName(),
                    b.getLatestVersion(), b.getSource().name(), b.getNotes());
        }
    }
}
