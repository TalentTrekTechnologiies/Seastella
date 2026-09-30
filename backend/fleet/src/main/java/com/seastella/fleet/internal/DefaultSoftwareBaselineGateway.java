package com.seastella.fleet.internal;

import com.seastella.fleet.api.SoftwareBaselineGateway;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link SoftwareBaselineGateway} over the {@code software_baseline} table.
 */
@Service
class DefaultSoftwareBaselineGateway implements SoftwareBaselineGateway {

    private final SoftwareBaselineRepository baselines;
    private final SpareRepository spares;
    private final VesselRepository vessels;

    DefaultSoftwareBaselineGateway(SoftwareBaselineRepository baselines, SpareRepository spares,
                                   VesselRepository vessels) {
        this.baselines = baselines;
        this.spares = spares;
        this.vessels = vessels;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OutdatedUnit> outdated() {
        Map<String, SoftwareBaseline> byKey = new LinkedHashMap<>();
        baselines.findAll().forEach(b -> byKey.putIfAbsent(b.getMatchKey(), b));
        if (byKey.isEmpty()) return List.of();

        Map<Long, Vessel> vesselById = new LinkedHashMap<>();
        vessels.findAll().forEach(v -> vesselById.put(v.getId(), v));

        List<OutdatedUnit> behind = new ArrayList<>();
        for (Spare spare : spares.findAll()) {
            if (spare.getSoftwareVersion() == null || spare.getSoftwareVersion().isBlank()) continue;

            String key = SoftwareMatchKey.of(spare.getMake(), spare.getModel());
            SoftwareBaseline baseline = key == null ? null : byKey.get(key);
            if (baseline == null) continue;

            // Only behind. Ahead is a stale sheet, and unknown is an empty cell.
            if (SoftwareVersions.compare(spare.getSoftwareVersion(), baseline.getLatestVersion())
                    != com.seastella.fleet.api.SoftwareStatus.OUTDATED) {
                continue;
            }

            Vessel vessel = vesselById.get(spare.getVesselId());
            behind.add(new OutdatedUnit(spare.getId(), spare.getName(), spare.getPath(),
                    spare.getVesselId(), vessel == null ? null : vessel.getName(),
                    vessel == null ? null : vessel.getOrganizationId(),
                    spare.getSoftwareVersion(), baseline.getLatestVersion()));
        }
        return behind;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Baseline> all() {
        return baselines.findAllByOrderByMakeAscModelAsc().stream()
                .map(DefaultSoftwareBaselineGateway::view)
                .toList();
    }

    @Override
    @Transactional
    public UpsertResult upsertFromSheet(List<Baseline> rows) {
        int added = 0;
        int updated = 0;
        int unchanged = 0;
        List<Skipped> skipped = new ArrayList<>();

        for (Baseline row : rows) {
            String key = SoftwareMatchKey.of(row.make(), row.model());
            if (key == null) {
                skipped.add(new Skipped(row.make(), row.model(),
                        "A baseline needs both a make and a model to match equipment on."));
                continue;
            }

            Optional<SoftwareBaseline> existing = baselines.findByMatchKey(key);
            if (existing.isEmpty()) {
                baselines.save(new SoftwareBaseline(row.make(), row.model(), row.equipmentName(),
                        row.latestVersion(), SoftwareBaseline.Source.IMPORTED));
                added++;
                continue;
            }

            SoftwareBaseline current = existing.get();
            if (current.getSource() == SoftwareBaseline.Source.RECORDED) {
                // Somebody corrected this by hand. That is normally the later
                // knowledge, so the sheet does not quietly undo it.
                skipped.add(new Skipped(row.make(), row.model(),
                        "Kept the version entered by hand (" + current.getLatestVersion() + ")."));
                continue;
            }

            if (Objects.equals(current.getLatestVersion(), row.latestVersion())) {
                unchanged++;
                continue;
            }

            current.setLatestVersion(row.latestVersion());
            if (row.equipmentName() != null && !row.equipmentName().isBlank()) {
                current.setEquipmentName(row.equipmentName());
            }
            updated++;
        }

        return new UpsertResult(added, updated, unchanged, skipped);
    }

    static Baseline view(SoftwareBaseline b) {
        return new Baseline(b.getId(), b.getMake(), b.getModel(), b.getEquipmentName(),
                b.getLatestVersion(), Origin.valueOf(b.getSource().name()), b.getNotes());
    }
}
