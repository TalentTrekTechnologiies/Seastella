package com.seastella.maintenance.internal;

import com.seastella.identity.api.ScopeGuard;
import com.seastella.maintenance.api.DueAssessment;
import com.seastella.maintenance.api.MaintenanceStatusEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Due status for every tracked spare on a vessel, from the one engine (MNT-07).
 * Spares without a rule are simply absent: "not tracked" is not a status to
 * colour.
 */
@RestController
@RequestMapping("/api/v1/vessels/{vesselId}/maintenance")
class MaintenanceController {

    private final SpareMaintenanceRuleRepository rules;
    private final MaintenanceStatusEngine engine;
    private final ScopeGuard scopeGuard;

    MaintenanceController(SpareMaintenanceRuleRepository rules, MaintenanceStatusEngine engine, ScopeGuard scopeGuard) {
        this.rules = rules;
        this.engine = engine;
        this.scopeGuard = scopeGuard;
    }

    @GetMapping
    @Transactional(readOnly = true)
    ResponseEntity<List<SpareDue>> forVessel(@PathVariable Long vesselId) {
        scopeGuard.assertVessel(vesselId);
        Set<Long> spareIds = new LinkedHashSet<>();
        rules.findByVesselIdInAndActiveTrue(Set.of(vesselId)).forEach(r -> spareIds.add(r.getSpareId()));

        LocalDate today = LocalDate.now();
        return ResponseEntity.ok(spareIds.stream()
                .map(id -> {
                    DueAssessment a = engine.assess(id, today);
                    return new SpareDue(id, a.status().name(), a.status().label(), a.daysRemaining(),
                            a.nextDueDate(), a.basis().name());
                })
                .toList());
    }

    record SpareDue(Long spareId, String status, String statusLabel, Integer daysRemaining,
                    LocalDate nextDueDate, String basis) {}
}
