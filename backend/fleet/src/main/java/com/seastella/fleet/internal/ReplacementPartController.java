package com.seastella.fleet.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.ForbiddenException;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.event.DomainEventPublisher;
import com.seastella.fleet.api.FleetEvents;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeGuard;
import com.seastella.identity.api.ScopeResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Replacement parts held on board (SoW s9.5): what is in the store, what the
 * minimum to hold is, and what is below it.
 *
 * <p>The Captain keeps the count, because they are the one opening the locker;
 * the Technical Head sets what the minimum should be. A count that takes a part
 * below its minimum raises an alert then and there (SPR-14) rather than waiting
 * for someone to read a report.
 */
@RestController
@RequestMapping("/api/v1")
class ReplacementPartController {

    private final ReplacementPartRepository parts;
    private final VesselRepository vessels;
    private final SpareRepository spares;
    private final ScopeGuard scopeGuard;
    private final ScopeResolver scopes;
    private final AuditService audit;
    private final DomainEventPublisher events;

    ReplacementPartController(ReplacementPartRepository parts, VesselRepository vessels, SpareRepository spares,
                              ScopeGuard scopeGuard, ScopeResolver scopes, AuditService audit,
                              DomainEventPublisher events) {
        this.parts = parts;
        this.vessels = vessels;
        this.spares = spares;
        this.scopeGuard = scopeGuard;
        this.scopes = scopes;
        this.audit = audit;
        this.events = events;
    }

    @GetMapping("/vessels/{vesselId}/parts")
    @Transactional(readOnly = true)
    ResponseEntity<List<PartView>> forVessel(@PathVariable Long vesselId) {
        scopeGuard.assertVessel(vesselId);
        return ResponseEntity.ok(parts.findByVesselIdIn(java.util.Set.of(vesselId)).stream()
                .sorted(Comparator.comparing(ReplacementPart::isBelowMinimum).reversed()
                        .thenComparing(ReplacementPart::getName))
                .map(this::view)
                .toList());
    }

    /** A stock count. The quantity is what is on the shelf now, not a delta. */
    @PutMapping("/parts/{partId}/stock")
    @Transactional
    ResponseEntity<PartView> count(@PathVariable Long partId, @RequestBody StockBody body) {
        AccessScope actor = scopes.currentScope();
        ReplacementPart part = load(partId);
        if (body == null || body.quantityOnHand() == null) {
            throw new ValidationException("Enter how many are on board.");
        }
        int quantity = body.quantityOnHand();
        if (quantity < 0 || quantity > 100_000) {
            throw new ValidationException("Enter a count between 0 and 100,000.");
        }
        assertMayCount(actor);

        int before = part.getQuantityOnHand();
        boolean wasBelow = part.isBelowMinimum();
        part.setQuantityOnHand(quantity);
        parts.save(part);

        Long organizationId = vessels.findById(part.getVesselId()).map(Vessel::getOrganizationId).orElse(null);
        audit.record(entry(actor, AuditAction.PART_STOCK_CHANGED, part, organizationId)
                .before(AuditJson.of("quantityOnHand", before))
                .after(AuditJson.of("quantityOnHand", quantity, "minimumQuantity", part.getMinimumQuantity(),
                        "note", text(body.note(), 500)))
                .build());

        events.publish(new FleetEvents.PartStockChanged(part.getId(), part.getName(), part.getPartNumber(),
                part.getVesselId(), vesselName(part.getVesselId()), organizationId, before, quantity,
                part.getMinimumQuantity(), part.isBelowMinimum() && !wasBelow, actor.userId(), Instant.now()));
        return ResponseEntity.ok(view(part));
    }

    /**
     * Adds a spare part the vessel must hold (GM 2.3.9.9).
     *
     * <p>The client keeps a minimum-spares form per vessel: the equipment, the
     * part, how many must be aboard, whether the vessel complies, and remarks.
     * This is one line of that form, entered by hand; the same rows arrive in
     * bulk through the spreadsheet import.
     *
     * <p>The minimum is kept twice on purpose — as a number, because the
     * below-minimum alert is arithmetic, and as the form's own words, because
     * "2 pcs each athwartship, fore and aft and Flinders bar" is not a number.
     */
    @PostMapping("/vessels/{vesselId}/parts")
    @Transactional
    ResponseEntity<PartView> add(@PathVariable Long vesselId, @RequestBody NewPartBody body) {
        AccessScope actor = scopes.currentScope();
        if (actor.role() != Role.PLATFORM_ADMIN && actor.role() != Role.TECHNICAL_HEAD) {
            throw ForbiddenException.ofAction("add to what this vessel must hold in stock");
        }
        scopeGuard.assertVessel(vesselId);
        if (body == null) throw new ValidationException("Enter the part's details.");

        String name = required(body.name(), 200);
        int minimum = body.minimumQuantity() == null ? 0 : body.minimumQuantity();
        int onHand = body.quantityOnHand() == null ? 0 : body.quantityOnHand();
        if (minimum < 0 || minimum > 100_000) throw new ValidationException("Enter a minimum between 0 and 100,000.");
        if (onHand < 0 || onHand > 100_000) throw new ValidationException("Enter a count between 0 and 100,000.");

        ReplacementPart part = new ReplacementPart(vesselId, name, onHand, minimum);
        part.setCritical(body.critical() == null || body.critical());
        part.setSpareId(equipmentOn(vesselId, body.spareId()));
        part.setPartNumber(text(body.partNumber(), 120));
        part.setManufacturer(text(body.manufacturer(), 120));
        part.setLocation(text(body.location(), 120));
        part.setExpiryDate(body.expiryDate());
        part.setMinimumNote(text(body.minimumNote(), 300));
        part.setCompliance(compliance(body.compliance()));
        part.setRemarks(text(body.remarks(), 1000));
        parts.save(part);

        Long organizationId = vessels.findById(vesselId).map(Vessel::getOrganizationId).orElse(null);
        audit.record(entry(actor, AuditAction.PART_STOCK_CHANGED, part, organizationId)
                .after(AuditJson.of("added", name, "minimumQuantity", minimum, "quantityOnHand", onHand,
                        "critical", part.isCritical(), "compliance", part.getCompliance()))
                .build());
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(view(part));
    }

    /**
     * What the vessel declares about one requirement: complies, does not, or
     * does not apply, with the remark that explains it.
     *
     * <p>Separate from the minimum itself because it answers a different
     * question and is reviewed on a different rhythm — the minimum is master
     * data, the compliance is this month's inventory check.
     */
    @PutMapping("/parts/{partId}/compliance")
    @Transactional
    ResponseEntity<PartView> declareCompliance(@PathVariable Long partId, @RequestBody ComplianceBody body) {
        AccessScope actor = scopes.currentScope();
        if (actor.role() != Role.PLATFORM_ADMIN && actor.role() != Role.TECHNICAL_HEAD) {
            throw ForbiddenException.ofAction("record compliance against the minimum spares");
        }
        ReplacementPart part = load(partId);
        String before = part.getCompliance();
        part.setCompliance(compliance(body == null ? null : body.compliance()));
        if (body != null && body.remarks() != null) part.setRemarks(text(body.remarks(), 1000));
        parts.save(part);

        Long organizationId = vessels.findById(part.getVesselId()).map(Vessel::getOrganizationId).orElse(null);
        audit.record(entry(actor, AuditAction.PART_STOCK_CHANGED, part, organizationId)
                .before(AuditJson.of("compliance", before))
                .after(AuditJson.of("compliance", part.getCompliance(), "remarks", part.getRemarks()))
                .build());
        return ResponseEntity.ok(view(part));
    }

    /** The equipment this part belongs to, if one was named, and only on this vessel. */
    private Long equipmentOn(Long vesselId, Long spareId) {
        if (spareId == null) return null;
        Spare spare = spares.findById(spareId)
                .orElseThrow(() -> NotFoundException.ofResource("Spare", spareId));
        if (!spare.getVesselId().equals(vesselId)) {
            throw NotFoundException.ofResource("Spare", spareId);
        }
        return spare.getId();
    }

    private static String compliance(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("YES", "NO", "NA").contains(value)) {
            throw new ValidationException("Compliance is Yes, No or NA.");
        }
        return value;
    }

    private static String required(String value, int max) {
        String t = value == null ? "" : value.trim();
        if (t.isEmpty()) throw new ValidationException("Give the part a name.");
        if (t.length() > max) throw new ValidationException("Keep the name under " + max + " characters.");
        return t;
    }

    /** What the vessel should always hold. The Technical Head's decision, not the Captain's. */
    @PutMapping("/parts/{partId}")
    @Transactional
    ResponseEntity<PartView> configure(@PathVariable Long partId, @RequestBody PartBody body) {
        AccessScope actor = scopes.currentScope();
        if (actor.role() != Role.PLATFORM_ADMIN && actor.role() != Role.TECHNICAL_HEAD) {
            throw ForbiddenException.ofAction("change what this vessel must hold in stock");
        }
        ReplacementPart part = load(partId);
        if (body == null || body.minimumQuantity() == null) {
            throw new ValidationException("Enter the minimum to hold.");
        }
        if (body.minimumQuantity() < 0 || body.minimumQuantity() > 100_000) {
            throw new ValidationException("Enter a minimum between 0 and 100,000.");
        }

        int before = part.getMinimumQuantity();
        boolean wasBelow = part.isBelowMinimum();
        part.setMinimumQuantity(body.minimumQuantity());
        if (body.location() != null) part.setLocation(text(body.location(), 120));
        if (body.expiryDate() != null) part.setExpiryDate(body.expiryDate());
        parts.save(part);

        Long organizationId = vessels.findById(part.getVesselId()).map(Vessel::getOrganizationId).orElse(null);
        audit.record(entry(actor, AuditAction.PART_STOCK_CHANGED, part, organizationId)
                .before(AuditJson.of("minimumQuantity", before))
                .after(AuditJson.of("minimumQuantity", part.getMinimumQuantity(),
                        "location", part.getLocation(), "expiryDate", part.getExpiryDate()))
                .build());

        // Raising the minimum can put a part short without anyone touching the shelf.
        if (part.isBelowMinimum() && !wasBelow) {
            events.publish(new FleetEvents.PartStockChanged(part.getId(), part.getName(), part.getPartNumber(),
                    part.getVesselId(), vesselName(part.getVesselId()), organizationId, part.getQuantityOnHand(),
                    part.getQuantityOnHand(), part.getMinimumQuantity(), true, actor.userId(), Instant.now()));
        }
        return ResponseEntity.ok(view(part));
    }

    // --------------------------------------------------------------- internals

    /** Everyone who works the vessel can count; the shore roles can correct a count. */
    private static void assertMayCount(AccessScope actor) {
        boolean allowed = switch (actor.role()) {
            case CAPTAIN, SHIP_MANAGER, TECHNICAL_HEAD, PLATFORM_ADMIN, SERVICE_ENGINEER -> true;
            default -> false;
        };
        if (!allowed) throw ForbiddenException.ofAction("record a stock count");
    }

    private ReplacementPart load(Long partId) {
        ReplacementPart part = parts.findById(partId)
                .orElseThrow(() -> NotFoundException.ofResource("ReplacementPart", partId));
        scopeGuard.assertVessel(part.getVesselId());
        return part;
    }

    private String vesselName(Long vesselId) {
        return vessels.findById(vesselId).map(Vessel::getName).orElse(null);
    }

    private PartView view(ReplacementPart part) {
        String spareName = part.getSpareId() == null ? null
                : spares.findById(part.getSpareId()).map(Spare::getName).orElse(null);
        String sparePath = part.getSpareId() == null ? null
                : spares.findById(part.getSpareId()).map(Spare::getPath).orElse(null);
        return new PartView(part.getId(), part.getVesselId(), part.getName(), part.getPartNumber(),
                part.getManufacturer(), part.getQuantityOnHand(), part.getMinimumQuantity(),
                part.isBelowMinimum(), part.getLocation(), part.getExpiryDate(), part.getSpareId(), spareName,
                sparePath, part.isCritical(), part.getMinimumNote(), part.getCompliance(), part.getRemarks());
    }

    private static AuditEntry.Builder entry(AccessScope actor, String action, ReplacementPart part, Long organizationId) {
        return AuditEntry.builder()
                .actor(actor.userId(), actor.role().name())
                .action(action)
                .entity("ReplacementPart", part.getId())
                .scope(organizationId, part.getVesselId());
    }

    private static String text(String value, int max) {
        if (value == null) return null;
        String t = value.trim();
        if (t.isEmpty()) return null;
        if (t.length() > max) throw new ValidationException("Keep this under " + max + " characters.");
        return t;
    }

    record PartView(Long id, Long vesselId, String name, String partNumber, String manufacturer,
                    int quantityOnHand, int minimumQuantity, boolean belowMinimum, String location,
                    LocalDate expiryDate, Long spareId, String spareName, String sparePath,
                    boolean critical, String minimumNote, String compliance, String remarks) {}

    record StockBody(Integer quantityOnHand, String note) {}

    record PartBody(Integer minimumQuantity, String location, LocalDate expiryDate) {}

    record NewPartBody(String name, Long spareId, String partNumber, String manufacturer,
                       Integer quantityOnHand, Integer minimumQuantity, String minimumNote,
                       String compliance, String remarks, String location, LocalDate expiryDate,
                       Boolean critical) {}

    record ComplianceBody(String compliance, String remarks) {}
}
