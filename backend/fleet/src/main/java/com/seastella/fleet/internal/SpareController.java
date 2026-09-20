package com.seastella.fleet.internal;

import com.seastella.core.api.error.NotFoundException;
import com.seastella.identity.api.ScopeGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A vessel's equipment fit, as the tree the VMP template defines.
 *
 * <p>Readable by anyone whose scope includes the vessel (RBAC matrix: view
 * spare tree). Out-of-scope vessels answer 404, never 403, so an id cannot be
 * used to probe which vessels exist.
 */
@RestController
@RequestMapping("/api/v1/vessels")
class SpareController {

    private final VesselRepository vessels;
    private final SpareRepository spares;
    private final EquipmentCategoryRepository categories;
    private final ScopeGuard scopeGuard;

    SpareController(VesselRepository vessels, SpareRepository spares,
                    EquipmentCategoryRepository categories, ScopeGuard scopeGuard) {
        this.vessels = vessels;
        this.spares = spares;
        this.categories = categories;
        this.scopeGuard = scopeGuard;
    }

    @GetMapping("/{vesselId}/spares")
    @Transactional(readOnly = true)
    ResponseEntity<VesselFit> spares(@PathVariable Long vesselId) {
        Vessel vessel = vessels.findById(vesselId)
                .orElseThrow(() -> NotFoundException.ofResource("Vessel", vesselId));
        scopeGuard.assertVessel(vesselId);

        Map<Long, EquipmentCategory> byId = categories.findAll().stream()
                .collect(Collectors.toMap(EquipmentCategory::getId, Function.identity()));

        List<SpareNode> nodes = spares.findByVesselIdOrderByPathAsc(vesselId).stream()
                .map(s -> {
                    EquipmentCategory c = byId.get(s.getEquipmentCategoryId());
                    return new SpareNode(
                            s.getId(), s.getParentSpareId(), s.getPath(), s.getDepth(), s.getName(),
                            c == null ? null : c.getCode(), c == null ? null : c.getName(),
                            s.getMake(), s.getModel(), s.getSerialNumber(),
                            s.getCriticality().name(), s.isTracksRunningHours(), s.getRunningHours(),
                            s.getSoftwareVersion(), s.getInstallationDate(), s.getExpirationDate(),
                            s.getLastAnnualServiceDate());
                })
                .toList();

        return ResponseEntity.ok(new VesselFit(vessel.getId(), vessel.getName(), nodes));
    }

    record VesselFit(Long vesselId, String vesselName, List<SpareNode> spares) {}

    record SpareNode(Long id, Long parentId, String path, int depth, String name,
                     String categoryCode, String categoryName,
                     String make, String model, String serialNumber,
                     String criticality, boolean tracksRunningHours, BigDecimal runningHours,
                     String softwareVersion, java.time.LocalDate installationDate,
                     java.time.LocalDate expirationDate, java.time.LocalDate lastAnnualServiceDate) {}
}
