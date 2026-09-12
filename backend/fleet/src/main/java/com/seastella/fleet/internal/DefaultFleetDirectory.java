package com.seastella.fleet.internal;

import com.seastella.fleet.api.FleetDirectory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    DefaultFleetDirectory(VesselRepository vessels, SpareRepository spares) {
        this.vessels = vessels;
        this.spares = spares;
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
}
