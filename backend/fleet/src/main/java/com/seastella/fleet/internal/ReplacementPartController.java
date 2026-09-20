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
        return new PartView(part.getId(), part.getVesselId(), part.getName(), part.getPartNumber(),
                part.getManufacturer(), part.getQuantityOnHand(), part.getMinimumQuantity(),
                part.isBelowMinimum(), part.getLocation(), part.getExpiryDate(), part.getSpareId(), spareName);
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
                    LocalDate expiryDate, Long spareId, String spareName) {}

    record StockBody(Integer quantityOnHand, String note) {}

    record PartBody(Integer minimumQuantity, String location, LocalDate expiryDate) {}
}
