package com.seastella.maintenance.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.maintenance.api.DueStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The colour bands, as configuration rather than constants (SoW s11, AUD-13).
 *
 * <p>Two of the four are definitions, not settings: a spare past its due date
 * is <b>Overdue</b> and one due today is <b>Due</b>, and no configuration can
 * make that untrue. What a fleet <em>can</em> decide is how much warning it
 * wants first: how many days out counts as <b>Urgent</b>, and how many as
 * <b>Approaching</b>. Everything beyond that is Normal.
 *
 * <p>The two ranges must meet - Approaching starts the day after Urgent ends -
 * so no spare falls between two colours. A change is audited and applied to
 * every tracked spare at once, rather than drifting in with the nightly scan.
 */
@RestController
@RequestMapping("/api/v1/maintenance/thresholds")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class MaintenanceThresholdController {

    private static final String URGENT = DueStatus.URGENT.name();
    private static final String APPROACHING = DueStatus.APPROACHING.name();

    private final MaintenanceThresholdRepository thresholds;
    private final SpareMaintenanceRuleRepository rules;
    private final ScopeResolver scopes;
    private final AuditService audit;
    private final ApplicationEventPublisher internalEvents;

    MaintenanceThresholdController(MaintenanceThresholdRepository thresholds, SpareMaintenanceRuleRepository rules,
                                   ScopeResolver scopes, AuditService audit,
                                   ApplicationEventPublisher internalEvents) {
        this.thresholds = thresholds;
        this.rules = rules;
        this.scopes = scopes;
        this.audit = audit;
        this.internalEvents = internalEvents;
    }

    @GetMapping
    @Transactional(readOnly = true)
    ResponseEntity<List<BandView>> bands() {
        return ResponseEntity.ok(ladder());
    }

    @PutMapping
    @Transactional
    ResponseEntity<List<BandView>> configure(@RequestBody BandsBody body) {
        if (body == null || body.urgentUpToDays() == null || body.approachingUpToDays() == null) {
            throw new ValidationException("Say how many days out counts as urgent, and how many as approaching.");
        }
        int urgentUpTo = body.urgentUpToDays();
        int approachingUpTo = body.approachingUpToDays();
        if (urgentUpTo < 1) {
            throw new ValidationException("Urgent must cover at least the day before it is due.");
        }
        if (approachingUpTo <= urgentUpTo) {
            throw new ValidationException("Approaching must reach further out than urgent, "
                    + "so the two bands meet rather than overlap.");
        }
        if (approachingUpTo > 365) {
            throw new ValidationException("Keep the warning inside a year; beyond that everything would be amber.");
        }

        MaintenanceThreshold urgent = platformBand(URGENT);
        MaintenanceThreshold approaching = platformBand(APPROACHING);
        if (Objects.equals(urgent.getMaxDays(), urgentUpTo)
                && Objects.equals(approaching.getMaxDays(), approachingUpTo)) {
            return ResponseEntity.ok(ladder());
        }
        String before = AuditJson.of("urgentUpToDays", urgent.getMaxDays(),
                "approachingUpToDays", approaching.getMaxDays());

        urgent.redefine(1, urgentUpTo);
        approaching.redefine(urgentUpTo + 1, approachingUpTo);
        thresholds.saveAll(List.of(urgent, approaching));

        AccessScope actor = scopes.currentScope();
        audit.record(AuditEntry.builder()
                .actor(actor.userId(), actor.role().name())
                .action(AuditAction.THRESHOLD_CHANGED)
                .entity("MaintenanceThreshold", urgent.getId())
                .before(before)
                .after(AuditJson.of("urgentUpToDays", urgentUpTo, "approachingUpToDays", approachingUpTo))
                .build());

        // Re-band every tracked spare now, so the dashboards, the colours and the
        // alerts agree with the new setting immediately.
        Set<Long> tracked = rules.findAll().stream()
                .map(SpareMaintenanceRule::getSpareId)
                .collect(Collectors.toSet());
        if (!tracked.isEmpty()) {
            internalEvents.publishEvent(new RulesChanged(tracked));
        }
        return ResponseEntity.ok(ladder());
    }

    // --------------------------------------------------------------- internals

    /** The whole ladder as the Platform Admin reads it, fixed bands included. */
    private List<BandView> ladder() {
        MaintenanceThreshold urgent = platformBand(URGENT);
        MaintenanceThreshold approaching = platformBand(APPROACHING);
        int urgentUpTo = urgent.getMaxDays() == null ? 9 : urgent.getMaxDays();
        int approachingUpTo = approaching.getMaxDays() == null ? 15 : approaching.getMaxDays();

        List<BandView> bands = new ArrayList<>();
        bands.add(band(DueStatus.OVERDUE, "Past its due date", false, null, null));
        bands.add(band(DueStatus.DUE, "Due today", false, 0, 0));
        bands.add(band(DueStatus.URGENT, "Within " + urgentUpTo + " days", true, 1, urgentUpTo));
        bands.add(band(DueStatus.APPROACHING, "Within " + approachingUpTo + " days", true,
                urgentUpTo + 1, approachingUpTo));
        bands.add(band(DueStatus.NORMAL, "More than " + approachingUpTo + " days away", false,
                approachingUpTo + 1, null));
        return bands;
    }

    private MaintenanceThreshold platformBand(String statusCode) {
        Optional<MaintenanceThreshold> found = thresholds.findAll().stream()
                .filter(t -> t.getOrganizationId() == null && t.isActive() && statusCode.equals(t.getStatusCode()))
                .findFirst();
        return found.orElseThrow(() -> NotFoundException.ofResource("MaintenanceThreshold", null));
    }

    private static BandView band(DueStatus status, String description, boolean configurable,
                                 Integer fromDays, Integer toDays) {
        return new BandView(status.name(), status.label(), status.colour(), description, configurable,
                fromDays, toDays);
    }

    record BandView(String statusCode, String label, String colour, String description,
                    boolean configurable, Integer fromDays, Integer toDays) {}

    record BandsBody(Integer urgentUpToDays, Integer approachingUpToDays) {}
}
