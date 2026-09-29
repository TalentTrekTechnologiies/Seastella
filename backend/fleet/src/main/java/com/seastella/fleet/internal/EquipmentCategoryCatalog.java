package com.seastella.fleet.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.ValidationException;
import com.seastella.identity.api.AccessScope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * Finds an equipment category by name, or adds it: a new kind of equipment -
 * a Magnetic Compass, an Aldis Lamp - that none of the VMP template's
 * categories covers. Used when equipment is added by hand and when a client's
 * own sheet names a kind the platform does not know yet.
 *
 * <p>A name that matches an existing category, ignoring case, spaces and
 * punctuation, returns that one instead of a twin. A new one takes the next
 * free VMP block: one past both the highest category number and the highest
 * top-level number any vessel already uses, so it can never land inside a
 * block that equipment already occupies.
 */
@Component
class EquipmentCategoryCatalog {

    private final EquipmentCategoryRepository categories;
    private final SpareRepository spares;
    private final AuditService audit;

    EquipmentCategoryCatalog(EquipmentCategoryRepository categories, SpareRepository spares, AuditService audit) {
        this.categories = categories;
        this.spares = spares;
        this.audit = audit;
    }

    static String tidy(String name) {
        String value = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (value.isEmpty()) throw new ValidationException("Name the new equipment type.");
        if (value.length() > 80) throw new ValidationException("Keep the name under 80 characters.");
        return value;
    }

    Optional<EquipmentCategory> find(String name) {
        String key = key(name);
        return categories.findAll().stream()
                .filter(c -> key(c.getName()).equals(key) || key(c.getCode()).equals(key))
                .findFirst();
    }

    /** The category with this name, created if there is none. The second value says whether it was created. */
    @Transactional
    Result ensure(String rawName, AccessScope actor, Long vesselId) {
        String name = tidy(rawName);
        Optional<EquipmentCategory> same = find(name);
        if (same.isPresent()) return new Result(same.get(), false);

        int highestCategory = categories.findAll().stream().mapToInt(EquipmentCategory::getDisplayOrder).max().orElse(0);
        int highestInUse = spares.topLevelPaths().stream()
                .map(p -> p == null ? "" : p.split("\\.", 2)[0].trim())
                .mapToInt(seg -> {
                    try {
                        return Integer.parseInt(seg);
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                })
                .max().orElse(0);
        int block = Math.max(highestCategory, highestInUse) + 1;

        String base = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        if (base.isEmpty()) base = "TYPE";
        if (base.length() > 34) base = base.substring(0, 34);
        String code = base;
        for (int n = 2; categories.findByCode(code).isPresent(); n++) code = base + "_" + n;

        EquipmentCategory saved = categories.save(new EquipmentCategory(code, name, block));
        audit.record(AuditEntry.builder()
                .actor(actor.userId(), actor.role() == null ? null : actor.role().name())
                .action(AuditAction.EQUIPMENT_CATEGORY_CREATED)
                .entity("EquipmentCategory", saved.getId())
                .scope(null, vesselId)
                .after(AuditJson.of("name", name, "code", code, "vmpBlock", block))
                .build());
        return new Result(saved, true);
    }

    private static String key(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    record Result(EquipmentCategory category, boolean created) {}
}
