package com.seastella.fleet.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
import com.seastella.fleet.api.Criticality;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.ScopeGuard;
import com.seastella.identity.api.ScopeResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Adding equipment and spares to a vessel by hand (SoW §9).
 *
 * <p>A vessel can be filled three ways: the standard bridge fit it is created
 * with, the VMP spreadsheet import for a whole fleet at once, and this — one
 * item at a time, which is what a Technical Head needs when a vessel carries
 * something the standard fit does not, or when a component is added to an
 * existing unit mid-life.
 *
 * <p>The VMP decimal structure is the point, so the path is derived rather
 * than typed: a new top-level item takes the next free number in its category,
 * and a child takes the next free number under its parent (13.1 → 13.1.4).
 * Typing paths by hand is how a tree stops being a tree.
 */
@RestController
@RequestMapping("/api/v1/vessels/{vesselId}/spares")
class SpareCreationController {

    /** A tree deeper than this is a data-entry mistake, not a real fit. */
    private static final int MAX_DEPTH = 5;

    private final SpareRepository spares;
    private final VesselRepository vessels;
    private final EquipmentCategoryRepository categories;
    private final ScopeGuard scopeGuard;
    private final ScopeResolver scopes;
    private final AuditService audit;

    SpareCreationController(SpareRepository spares, VesselRepository vessels,
                            EquipmentCategoryRepository categories, ScopeGuard scopeGuard,
                            ScopeResolver scopes, AuditService audit) {
        this.spares = spares;
        this.vessels = vessels;
        this.categories = categories;
        this.scopeGuard = scopeGuard;
        this.scopes = scopes;
        this.audit = audit;
    }

    /**
     * The equipment categories a top-level item can be filed under (SoW §9.2).
     *
     * <p>Served beside the vessel rather than as a global list because that is
     * where the choice is made, and because the caller has already proved they
     * may see this vessel.
     */
    @GetMapping("/categories")
    @Transactional(readOnly = true)
    ResponseEntity<List<CategoryView>> categories(@PathVariable Long vesselId) {
        scopeGuard.assertVessel(vesselId);
        return ResponseEntity.ok(categories.findAll().stream()
                .sorted(Comparator.comparingInt(EquipmentCategory::getDisplayOrder))
                .map(c -> new CategoryView(c.getId(), c.getCode(), c.getName(), c.getDisplayOrder()))
                .toList());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TECHNICAL_HEAD')")
    @Transactional
    ResponseEntity<SpareView> add(@PathVariable Long vesselId, @RequestBody SpareBody body) {
        AccessScope actor = scopes.currentScope();
        scopeGuard.assertVessel(vesselId);
        Vessel vessel = vessels.findById(vesselId)
                .orElseThrow(() -> NotFoundException.ofResource("Vessel", vesselId));
        if (body == null) throw new ValidationException("Give the equipment's details.");

        String name = required(body.name(), 200);
        Spare parent = parentOf(body.parentSpareId(), vesselId);
        Long categoryId = categoryFor(body, parent);
        String path = nextPath(vesselId, parent, categoryId);
        if (path.chars().filter(c -> c == '.').count() >= MAX_DEPTH) {
            throw new WorkflowException("That is too deep to be a real equipment structure.");
        }

        Spare spare = new Spare(vesselId, categoryId, path, name);
        if (parent != null) spare.setParentSpareId(parent.getId());
        spare.setMake(text(body.make(), 120));
        spare.setModel(text(body.model(), 120));
        spare.setSerialNumber(text(body.serialNumber(), 120));
        spare.setSoftwareVersion(text(body.softwareVersion(), 64));
        spare.setInstallationDate(notFuture(body.installationDate(), "installation date"));
        spare.setExpirationDate(body.expirationDate());
        // Deliberately not set here: the last annual service date is the first
        // row of the service history, and is entered there so the history and
        // the date can never tell different stories.
        if (body.criticality() != null) spare.setCriticality(body.criticality());
        if (Boolean.TRUE.equals(body.tracksRunningHours())) {
            spare.enableRunningHours(java.math.BigDecimal.ZERO);
        }

        Spare saved = spares.save(spare);
        audit.record(AuditEntry.builder()
                .actor(actor.userId(), actor.role().name())
                .action(AuditAction.SPARE_UPDATED)
                .entity("Spare", saved.getId())
                .scope(vessel.getOrganizationId(), vesselId)
                .after(AuditJson.of("added", name, "path", path, "parent", parent == null ? null : parent.getPath(),
                        "make", saved.getMake(), "model", saved.getModel(), "serialNumber", saved.getSerialNumber()))
                .build());

        return ResponseEntity.status(HttpStatus.CREATED).body(new SpareView(
                saved.getId(), saved.getPath(), saved.getName(), saved.getParentSpareId(),
                saved.getEquipmentCategoryId(), saved.getDepth(), saved.getCriticality(),
                saved.isTracksRunningHours()));
    }

    // -------------------------------------------------------------- internals

    private Spare parentOf(Long parentSpareId, Long vesselId) {
        if (parentSpareId == null) return null;
        Spare parent = spares.findById(parentSpareId)
                .orElseThrow(() -> NotFoundException.ofResource("Spare", parentSpareId));
        if (!parent.getVesselId().equals(vesselId)) {
            // Another vessel's equipment is not a parent; it is not even visible.
            throw NotFoundException.ofResource("Spare", parentSpareId);
        }
        return parent;
    }

    /** A child belongs to its parent's category; a root needs one chosen. */
    private Long categoryFor(SpareBody body, Spare parent) {
        if (parent != null) return parent.getEquipmentCategoryId();
        if (body.equipmentCategoryId() == null) {
            throw new ValidationException("Choose the equipment category this belongs to.");
        }
        if (!categories.existsById(body.equipmentCategoryId())) {
            throw NotFoundException.ofResource("EquipmentCategory", body.equipmentCategoryId());
        }
        return body.equipmentCategoryId();
    }

    /**
     * The next free number, in VMP decimal order.
     *
     * <p>Derived rather than typed, because the decimal structure is the whole
     * point of the tree: a child takes the next number under its parent
     * (13.1 → 13.1.4), and a new top-level item takes the next number in its
     * category's block. Compared numerically, not as text — otherwise 13.10
     * sorts before 13.9 and the next number is wrong.
     */
    private String nextPath(Long vesselId, Spare parent, Long categoryId) {
        if (parent != null) {
            List<Spare> children = spares.findByParentSpareId(parent.getId());
            return parent.getPath() + "." + (highestSegment(children) + 1);
        }

        List<Spare> onThisVessel = spares.findByVesselIdOrderByPathAsc(vesselId).stream()
                .filter(sp -> sp.getParentSpareId() == null)
                .filter(sp -> sp.getEquipmentCategoryId().equals(categoryId))
                .toList();
        String block = categoryBlock(categoryId, onThisVessel);

        if (onThisVessel.isEmpty()) {
            // First of its category aboard: it takes the block number itself,
            // exactly as the standard fit does.
            return block;
        }
        // Already one or more: 13 → 13, then 13.1, 13.2 as siblings beneath it
        // is how the VMP nests, so a second top-level unit of the same category
        // becomes the next whole number in that block's family.
        int next = onThisVessel.stream()
                .map(Spare::getPath)
                .map(SpareCreationController::lastSegment)
                .max(Comparator.naturalOrder())
                .orElse(0) + 1;
        return block.equals(String.valueOf(next)) ? block + ".1" : block + "." + next;
    }

    /**
     * Which decimal block a category occupies. Taken from equipment already
     * filed under it — the VMP numbering is the client's, not ours to invent —
     * and only falling back to the category's display order when this platform
     * has never seen that category numbered.
     */
    private String categoryBlock(Long categoryId, List<Spare> onThisVessel) {
        Optional<String> fromVessel = onThisVessel.stream().map(Spare::getPath).findFirst();
        if (fromVessel.isPresent()) return firstSegment(fromVessel.get());

        Optional<String> anywhere = spares.findFirstByEquipmentCategoryIdAndParentSpareIdIsNullOrderByIdAsc(categoryId)
                .map(Spare::getPath);
        if (anywhere.isPresent()) return firstSegment(anywhere.get());

        return categories.findById(categoryId)
                .map(EquipmentCategory::getDisplayOrder)
                .map(String::valueOf)
                .orElseThrow(() -> new WorkflowException(
                        "That equipment category has no VMP number yet, so a path cannot be derived."));
    }

    private static int highestSegment(List<Spare> siblings) {
        return siblings.stream()
                .map(Spare::getPath)
                .map(SpareCreationController::lastSegment)
                .max(Comparator.naturalOrder())
                .orElse(0);
    }

    private static String firstSegment(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }

    private static int lastSegment(String path) {
        int dot = path.lastIndexOf('.');
        try {
            return Integer.parseInt(dot < 0 ? path : path.substring(dot + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static LocalDate notFuture(LocalDate date, String what) {
        if (date != null && date.isAfter(LocalDate.now(java.time.ZoneOffset.UTC))) {
            throw new ValidationException("The " + what + " cannot be in the future.");
        }
        return date;
    }

    private static String required(String value, int max) {
        String t = value == null ? "" : value.trim();
        if (t.isEmpty()) throw new ValidationException("Give the equipment a name.");
        if (t.length() > max) throw new ValidationException("Keep the name under " + max + " characters.");
        return t;
    }

    private static String text(String value, int max) {
        if (value == null) return null;
        String t = value.trim();
        if (t.isEmpty()) return null;
        if (t.length() > max) throw new ValidationException("Keep this under " + max + " characters.");
        return t;
    }

    record SpareBody(String name, Long parentSpareId, Long equipmentCategoryId, String make, String model,
                     String serialNumber, String softwareVersion, LocalDate installationDate,
                     LocalDate expirationDate, Criticality criticality, Boolean tracksRunningHours) {}

    record CategoryView(Long id, String code, String name, int displayOrder) {}

    record SpareView(Long id, String path, String name, Long parentSpareId, Long equipmentCategoryId,
                     short depth, Criticality criticality, boolean tracksRunningHours) {}
}
