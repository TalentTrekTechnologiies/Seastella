package com.seastella.fleet.internal;

import com.seastella.fleet.api.FleetDirectory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one implementation of {@link FleetDirectory}.
 *
 * <p>{@code maintenance} consumes this to resolve a spare's organization when
 * applying per-organization thresholds. It cannot go the other way round -
 * {@code maintenance} depends on {@code fleet}, so the port lives here.
 */
@Component
class DefaultFleetDirectory implements FleetDirectory {

    private final VesselRepository vessels;
    private final SpareRepository spares;
    private final EquipmentCategoryRepository categories;
    private final OrganizationRepository organizations;
    private final RunningHourReadingRepository readings;

    DefaultFleetDirectory(VesselRepository vessels, SpareRepository spares,
                          EquipmentCategoryRepository categories,
                          OrganizationRepository organizations,
                          RunningHourReadingRepository readings) {
        this.vessels = vessels;
        this.spares = spares;
        this.categories = categories;
        this.organizations = organizations;
        this.readings = readings;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> organizationCodeForVessel(Long vesselId) {
        if (vesselId == null) return Optional.empty();
        return vessels.findById(vesselId)
                .flatMap(v -> organizations.findById(v.getOrganizationId()))
                .map(Organization::getCode);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> vesselIdsForOrganization(Long organizationId) {
        if (organizationId == null) {
            return Set.of();
        }
        return vessels.findIdsByOrganizationId(organizationId);
    }

    @Override
    @Transactional(readOnly = true)
    public Long organizationIdForVessel(Long vesselId) {
        if (vesselId == null) return null;
        return vessels.findById(vesselId).map(Vessel::getOrganizationId).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public Long organizationIdForSpare(Long spareId) {
        Long vesselId = vesselIdForSpare(spareId);
        return vesselId == null ? null : organizationIdForVessel(vesselId);
    }

    @Override
    @Transactional(readOnly = true)
    public Long vesselIdForSpare(Long spareId) {
        if (spareId == null) return null;
        return spares.findById(spareId).map(Spare::getVesselId).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Boolean> spareIdsWithHourTracking(Long vesselId) {
        Map<Long, Boolean> result = new LinkedHashMap<>();
        if (vesselId == null) {
            return result;
        }
        for (Spare spare : spares.findByVesselIdOrderByPathAsc(vesselId)) {
            result.put(spare.getId(), spare.isTracksRunningHours());
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> equipmentCategoryIdByCode(String code) {
        return categories.findByCode(code).map(EquipmentCategory::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryRef> equipmentCategories() {
        return categories.findAll(org.springframework.data.domain.Sort.by("displayOrder")).stream()
                .map(c -> new CategoryRef(c.getId(), c.getCode(), c.getName()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean organizationExists(Long organizationId) {
        return organizationId != null && organizations.existsById(organizationId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SpareRef> spareRef(Long spareId) {
        if (spareId == null) return Optional.empty();
        return spares.findById(spareId).flatMap(s -> vessels.findById(s.getVesselId()).map(v ->
                new SpareRef(s.getId(), s.getName(), s.getPath(), v.getId(), v.getName(),
                        v.getOrganizationId(), s.isTracksRunningHours(), s.getRunningHours())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<HourReading> hourReadings(Long spareId, int limit) {
        if (spareId == null) return List.of();
        return readings.findTop24BySpareIdOrderByReadingDateDescIdDesc(spareId).stream()
                .limit(Math.max(0, limit))
                .map(r -> new HourReading(r.getReadingDate(), r.getReadingHours()))
                .toList();
    }
}
