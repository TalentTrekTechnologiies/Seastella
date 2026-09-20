package com.seastella.fleet.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.event.DomainEventPublisher;
import com.seastella.fleet.api.Criticality;
import com.seastella.fleet.api.FleetEvents;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.ScopeGuard;
import com.seastella.identity.api.ScopeResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Editing a spare's own facts (SoW s9.3; RBAC: edit spare master - Platform
 * Admin, Technical Head for their organization).
 *
 * <p>These are the details a new vessel's standard fit starts without: make,
 * model, serial number, software version and dates. The last annual service
 * date matters beyond the record - it is what starts maintenance tracking, so
 * a change to it is published for the maintenance module and audited in its
 * own right (AUD-14).
 */
@RestController
@RequestMapping("/api/v1/spares")
class SpareMasterController {

    private final SpareRepository spares;
    private final VesselRepository vessels;
    private final ScopeGuard scopeGuard;
    private final ScopeResolver scopes;
    private final AuditService audit;
    private final DomainEventPublisher events;

    SpareMasterController(SpareRepository spares, VesselRepository vessels, ScopeGuard scopeGuard,
                          ScopeResolver scopes, AuditService audit, DomainEventPublisher events) {
        this.spares = spares;
        this.vessels = vessels;
        this.scopeGuard = scopeGuard;
        this.scopes = scopes;
        this.audit = audit;
        this.events = events;
    }

    @PutMapping("/{spareId}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TECHNICAL_HEAD')")
    @Transactional
    ResponseEntity<SpareDetails> update(@PathVariable Long spareId, @RequestBody SpareDetails body) {
        Spare spare = spares.findById(spareId).orElseThrow(() -> NotFoundException.ofResource("Spare", spareId));
        scopeGuard.assertVessel(spare.getVesselId());
        if (body == null) throw new ValidationException("Enter the spare's details.");

        LocalDate today = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        if (body.installationDate() != null && body.installationDate().isAfter(today)) {
            throw new ValidationException("The installation date cannot be in the future.");
        }
        if (body.lastAnnualServiceDate() != null && body.lastAnnualServiceDate().isAfter(today)) {
            throw new ValidationException("The last annual service date cannot be in the future.");
        }
        if (body.installationDate() != null && body.lastAnnualServiceDate() != null
                && body.lastAnnualServiceDate().isBefore(body.installationDate())) {
            throw new ValidationException("The last annual service cannot be before the installation date.");
        }
        Criticality criticality;
        try {
            criticality = body.criticality() == null ? spare.getCriticality() : Criticality.valueOf(body.criticality());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Criticality must be CRITICAL, HIGH, MEDIUM or LOW.");
        }

        SpareDetails before = SpareDetails.of(spare);
        spare.setMake(text(body.make(), 120, "make"));
        spare.setModel(text(body.model(), 120, "model"));
        spare.setSerialNumber(text(body.serialNumber(), 120, "serial number"));
        spare.setSoftwareVersion(text(body.softwareVersion(), 64, "software version"));
        spare.setInstallationDate(body.installationDate());
        spare.setExpirationDate(body.expirationDate());
        spare.setLastAnnualServiceDate(body.lastAnnualServiceDate());
        spare.setCriticality(criticality);
        spares.save(spare);
        SpareDetails after = SpareDetails.of(spare);

        AccessScope scope = scopes.currentScope();
        Long organizationId = vessels.findById(spare.getVesselId()).map(Vessel::getOrganizationId).orElse(null);
        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role().name())
                .action(AuditAction.SPARE_UPDATED)
                .entity("Spare", spareId)
                .scope(organizationId, spare.getVesselId())
                .before(before.json())
                .after(after.json())
                .build());

        if (!Objects.equals(before.lastAnnualServiceDate(), after.lastAnnualServiceDate())) {
            audit.record(AuditEntry.builder()
                    .actor(scope.userId(), scope.role().name())
                    .action(AuditAction.SERVICE_DATE_CHANGED)
                    .entity("Spare", spareId)
                    .scope(organizationId, spare.getVesselId())
                    .before(AuditJson.of("lastAnnualServiceDate", before.lastAnnualServiceDate()))
                    .after(AuditJson.of("lastAnnualServiceDate", after.lastAnnualServiceDate()))
                    .build());
            events.publish(new FleetEvents.ServiceDateChanged(spareId, spare.getVesselId(), organizationId,
                    before.lastAnnualServiceDate(), after.lastAnnualServiceDate(), scope.userId(), Instant.now()));
        }
        return ResponseEntity.ok(after);
    }

    private static String text(String value, int max, String what) {
        if (value == null) return null;
        String t = value.trim();
        if (t.isEmpty()) return null;
        if (t.length() > max) throw new ValidationException("Keep the " + what + " under " + max + " characters.");
        return t;
    }

    record SpareDetails(String make, String model, String serialNumber, String softwareVersion,
                        LocalDate installationDate, LocalDate expirationDate, LocalDate lastAnnualServiceDate,
                        String criticality) {

        static SpareDetails of(Spare s) {
            return new SpareDetails(s.getMake(), s.getModel(), s.getSerialNumber(), s.getSoftwareVersion(),
                    s.getInstallationDate(), s.getExpirationDate(), s.getLastAnnualServiceDate(),
                    s.getCriticality().name());
        }

        String json() {
            return AuditJson.of("make", make, "model", model, "serialNumber", serialNumber,
                    "softwareVersion", softwareVersion, "installationDate", installationDate,
                    "expirationDate", expirationDate, "lastAnnualServiceDate", lastAnnualServiceDate,
                    "criticality", criticality);
        }
    }
}
