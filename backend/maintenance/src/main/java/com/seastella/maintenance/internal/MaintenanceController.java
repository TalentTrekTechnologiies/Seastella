package com.seastella.maintenance.internal;

import com.seastella.core.api.time.BusinessTime;
import com.seastella.fleet.api.FleetDirectory;
import com.seastella.identity.api.ScopeGuard;
import com.seastella.maintenance.api.DueAssessment;
import com.seastella.maintenance.api.DueStatus;
import com.seastella.maintenance.api.MaintenanceStatusEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The dated statuses for a vessel's equipment, from the one engine (MNT-07).
 *
 * <h2>Two dates, one ladder</h2>
 * A unit can be waiting on two different clocks. Its <b>service</b> falls due
 * on a recurring cycle - that is {@code status}, and a unit with no
 * maintenance rule simply has none. Its <b>expiry</b> is the day the unit
 * itself stops being fit for use, after which no amount of servicing makes it
 * compliant: a life-limited battery, a hydrostatic release, a liferaft bottle.
 *
 * <p>Both are read through {@link MaintenanceStatusEngine#classify}, so a
 * yellow expiry and a yellow service mean the same number of days and move
 * together when the Platform Admin changes the bands. Deriving the second one
 * in the browser would have been a second answer to the same question.
 *
 * <p>A unit appears here if it has either a rule or an expiry date. It may
 * carry one without the other, so {@code status} can be NOT_TRACKED on a row
 * whose expiry is red, and {@code expiryStatus} is absent on a row that is
 * merely serviced on a cycle.
 */
@RestController
@RequestMapping("/api/v1/vessels/{vesselId}/maintenance")
class MaintenanceController {

    private final SpareMaintenanceRuleRepository rules;
    private final MaintenanceStatusEngine engine;
    private final FleetDirectory fleet;
    private final ScopeGuard scopeGuard;

    MaintenanceController(SpareMaintenanceRuleRepository rules, MaintenanceStatusEngine engine,
                          FleetDirectory fleet, ScopeGuard scopeGuard) {
        this.rules = rules;
        this.engine = engine;
        this.fleet = fleet;
        this.scopeGuard = scopeGuard;
    }

    @GetMapping
    @Transactional(readOnly = true)
    ResponseEntity<List<SpareDue>> forVessel(@PathVariable Long vesselId) {
        scopeGuard.assertVessel(vesselId);

        Map<Long, LocalDate> expiryBySpare = new LinkedHashMap<>();
        fleet.equipmentExpiries(vesselId)
                .forEach(e -> expiryBySpare.put(e.spareId(), e.expiryDate()));

        Set<Long> spareIds = new LinkedHashSet<>();
        rules.findByVesselIdInAndActiveTrue(Set.of(vesselId)).forEach(r -> spareIds.add(r.getSpareId()));
        spareIds.addAll(expiryBySpare.keySet());

        LocalDate today = BusinessTime.today();
        Long organizationId = fleet.organizationIdForVessel(vesselId);

        return ResponseEntity.ok(spareIds.stream()
                .map(id -> {
                    DueAssessment a = engine.assess(id, today);
                    LocalDate expiry = expiryBySpare.get(id);
                    if (expiry == null) {
                        return new SpareDue(id, a.status().name(), a.status().label(), a.daysRemaining(),
                                a.nextDueDate(), a.basis().name(), null, null, null, null);
                    }
                    int daysToExpiry = (int) ChronoUnit.DAYS.between(today, expiry);
                    DueStatus expiryStatus = engine.classify(daysToExpiry, organizationId);
                    return new SpareDue(id, a.status().name(), a.status().label(), a.daysRemaining(),
                            a.nextDueDate(), a.basis().name(),
                            expiry, daysToExpiry, expiryStatus.name(), expiryStatus.label());
                })
                .toList());
    }

    /**
     * @param status      the service cycle's band, NOT_TRACKED when no rule applies
     * @param expiryDate  the day the unit stops being fit for use, null when none is recorded
     * @param expiryStatus that date on the same colour ladder, null when there is no date
     */
    record SpareDue(Long spareId, String status, String statusLabel, Integer daysRemaining,
                    LocalDate nextDueDate, String basis,
                    LocalDate expiryDate, Integer daysToExpiry,
                    String expiryStatus, String expiryStatusLabel) {}
}
