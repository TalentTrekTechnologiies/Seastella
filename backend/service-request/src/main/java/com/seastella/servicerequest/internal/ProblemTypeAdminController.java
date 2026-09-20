package com.seastella.servicerequest.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
import com.seastella.fleet.api.FleetDirectory;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.ScopeResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Problem types, maintained by the Platform Admin (SoW s13: "Seastella can add
 * new Spare/problem combinations without code changes").
 *
 * <p>A problem type belongs to one equipment category. Its code is fixed when
 * it is created, because flows and past requests refer to it; the label can
 * change, and retiring it stops Captains choosing it without touching history.
 */
@RestController
@RequestMapping("/api/v1/problem-types")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class ProblemTypeAdminController {

    private static final int MAX_PER_CATEGORY = 50;

    private final ProblemTypeRepository problemTypes;
    private final FleetDirectory fleet;
    private final ScopeResolver scopes;
    private final AuditService audit;

    ProblemTypeAdminController(ProblemTypeRepository problemTypes, FleetDirectory fleet, ScopeResolver scopes,
                               AuditService audit) {
        this.problemTypes = problemTypes;
        this.fleet = fleet;
        this.scopes = scopes;
        this.audit = audit;
    }

    /** Every equipment category with its problem types, retired ones included. */
    @GetMapping
    @Transactional(readOnly = true)
    ResponseEntity<List<CategoryView>> list() {
        Map<Long, Long> inUse = new HashMap<>();
        for (Object[] row : problemTypes.requestCounts()) {
            inUse.put((Long) row[0], (Long) row[1]);
        }
        Map<Long, List<ProblemTypeView>> byCategory = new HashMap<>();
        for (ProblemType p : problemTypes.findAllByOrderByEquipmentCategoryIdAscDisplayOrderAsc()) {
            byCategory.computeIfAbsent(p.getEquipmentCategoryId(), k -> new ArrayList<>())
                    .add(view(p, inUse.getOrDefault(p.getId(), 0L)));
        }
        return ResponseEntity.ok(fleet.equipmentCategories().stream()
                .map(c -> new CategoryView(c.id(), c.code(), c.name(), byCategory.getOrDefault(c.id(), List.of())))
                .toList());
    }

    @PostMapping
    @Transactional
    ResponseEntity<ProblemTypeView> create(@RequestBody CreateBody body) {
        if (body == null || body.equipmentCategoryId() == null) {
            throw new ValidationException("Choose the equipment the problem applies to.");
        }
        FleetDirectory.CategoryRef category = fleet.equipmentCategories().stream()
                .filter(c -> c.id().equals(body.equipmentCategoryId())).findFirst()
                .orElseThrow(() -> NotFoundException.ofResource("EquipmentCategory", body.equipmentCategoryId()));
        String label = label(body.label());
        List<ProblemType> existing = problemTypes.findByEquipmentCategoryIdOrderByDisplayOrderAsc(category.id());
        assertLabelFree(existing, label, null, category.name());
        if (existing.size() >= MAX_PER_CATEGORY) {
            throw new WorkflowException(category.name() + " already has " + MAX_PER_CATEGORY
                    + " problem types. Retire or rename one instead.");
        }

        int order = existing.stream().mapToInt(ProblemType::getDisplayOrder).max().orElse(0) + 1;
        ProblemType created = problemTypes.save(new ProblemType(category.id(), uniqueCode(category.code(), label), label, order));

        record(AuditAction.PROBLEM_TYPE_CREATED, created, null,
                AuditJson.of("category", category.name(), "code", created.getCode(), "label", label));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(created, 0));
    }

    /** Rename, retire or bring back. The code never changes. */
    @PutMapping("/{id}")
    @Transactional
    ResponseEntity<ProblemTypeView> update(@PathVariable Long id, @RequestBody UpdateBody body) {
        ProblemType p = problemTypes.findById(id).orElseThrow(() -> NotFoundException.ofResource("ProblemType", id));
        if (body == null || (body.label() == null && body.active() == null)) {
            throw new ValidationException("Nothing to change.");
        }
        String before = AuditJson.of("label", p.getLabel(), "active", p.isActive());
        if (body.label() != null) {
            String label = label(body.label());
            assertLabelFree(problemTypes.findByEquipmentCategoryIdOrderByDisplayOrderAsc(p.getEquipmentCategoryId()),
                    label, p.getId(), "this equipment");
            p.rename(label);
        }
        if (body.active() != null) {
            p.setActive(body.active());
        }
        problemTypes.save(p);
        record(AuditAction.PROBLEM_TYPE_UPDATED, p, before,
                AuditJson.of("code", p.getCode(), "label", p.getLabel(), "active", p.isActive()));
        long used = problemTypes.requestCounts().stream()
                .filter(r -> p.getId().equals(r[0])).mapToLong(r -> (Long) r[1]).sum();
        return ResponseEntity.ok(view(p, used));
    }

    /** The order Captains see, for one equipment category: every problem type of it, in the new order. */
    @PutMapping("/order")
    @Transactional
    ResponseEntity<Void> reorder(@RequestBody OrderBody body) {
        if (body == null || body.equipmentCategoryId() == null || body.problemTypeIds() == null
                || body.problemTypeIds().stream().anyMatch(Objects::isNull)) {
            throw new ValidationException("Give the equipment and its problem types in order.");
        }
        List<ProblemType> current = problemTypes.findByEquipmentCategoryIdOrderByDisplayOrderAsc(body.equipmentCategoryId());
        List<Long> currentIds = current.stream().map(ProblemType::getId).sorted().toList();
        List<Long> requested = body.problemTypeIds().stream().sorted().toList();
        if (!currentIds.equals(requested)) {
            // A stale screen, or ids from another category: never half-apply an order.
            throw new WorkflowException("The problem types have changed since this list was loaded. Reload and try again.");
        }
        Map<Long, ProblemType> byId = new HashMap<>();
        current.forEach(p -> byId.put(p.getId(), p));
        String before = AuditJson.of("order", current.stream().map(ProblemType::getCode).toList());
        int order = 1;
        for (Long id : body.problemTypeIds()) {
            byId.get(id).moveTo(order++);
        }
        problemTypes.saveAll(current);

        AccessScope scope = scopes.currentScope();
        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role().name())
                .action(AuditAction.PROBLEM_TYPE_UPDATED)
                .entity("EquipmentCategory", body.equipmentCategoryId())
                .before(before)
                .after(AuditJson.of("order", body.problemTypeIds().stream().map(id -> byId.get(id).getCode()).toList()))
                .build());
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- internals

    private void record(String action, ProblemType p, String before, String after) {
        AccessScope scope = scopes.currentScope();
        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role().name())
                .action(action)
                .entity("ProblemType", p.getId())
                .before(before)
                .after(after)
                .build());
    }

    private static String label(String raw) {
        String t = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) throw new ValidationException("Describe the problem, e.g. \"No echoes on the display\".");
        if (t.length() > 160) throw new ValidationException("Keep the problem description under 160 characters.");
        return t;
    }

    private static void assertLabelFree(List<ProblemType> inCategory, String label, Long exceptId, String where) {
        boolean taken = inCategory.stream()
                .anyMatch(p -> !p.getId().equals(exceptId) && p.getLabel().equalsIgnoreCase(label));
        if (taken) {
            throw new WorkflowException("\"" + label + "\" is already a problem type for " + where
                    + ". Bring that one back if it was retired.");
        }
    }

    /** RADAR + "No echoes on display" gives RADAR_NO_ECHOES_ON_DISPLAY, made unique if needed. */
    private String uniqueCode(String categoryCode, String label) {
        String slug = Normalizer.normalize(label, Normalizer.Form.NFD)
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toUpperCase(Locale.ROOT);
        String base = (categoryCode + "_" + (slug.isEmpty() ? "PROBLEM" : slug));
        if (base.length() > 54) base = base.substring(0, 54).replaceAll("_+$", "");
        String code = base;
        for (int n = 2; problemTypes.existsByCode(code); n++) {
            code = base + "_" + n;
        }
        return code;
    }

    private static ProblemTypeView view(ProblemType p, long requestCount) {
        return new ProblemTypeView(p.getId(), p.getEquipmentCategoryId(), p.getCode(), p.getLabel(),
                p.getDisplayOrder(), p.isActive(), requestCount);
    }

    record CategoryView(Long id, String code, String name, List<ProblemTypeView> problemTypes) {}

    record ProblemTypeView(Long id, Long equipmentCategoryId, String code, String label, int displayOrder,
                           boolean active, long requestCount) {}

    record CreateBody(Long equipmentCategoryId, String label) {}

    record UpdateBody(String label, Boolean active) {}

    record OrderBody(Long equipmentCategoryId, List<Long> problemTypeIds) {}
}
