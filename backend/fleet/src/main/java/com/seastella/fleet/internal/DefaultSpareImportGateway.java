package com.seastella.fleet.internal;

import com.seastella.core.api.event.DomainEventPublisher;
import com.seastella.fleet.api.Criticality;
import com.seastella.fleet.api.FleetEvents;
import com.seastella.fleet.api.SpareImportGateway;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.ScopeResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The fleet side of a VMP import.
 *
 * <p>Writes run inside the caller's transaction ({@code MANDATORY}) so a commit
 * is all-or-nothing: an import never leaves half a vessel's equipment updated
 * (NFR-03). Per-row audit entries are deliberately not written here - the
 * import batch keeps the before and after of every row and is audited as one
 * event (AUD-07), which keeps the Platform Admin's feed readable when 200 rows
 * land at once.
 */
@Component
class DefaultSpareImportGateway implements SpareImportGateway {

    private final VesselRepository vessels;
    private final SpareRepository spares;
    private final EquipmentCategoryRepository categories;
    private final ScopeResolver scopes;
    private final DomainEventPublisher events;

    DefaultSpareImportGateway(VesselRepository vessels, SpareRepository spares,
                              EquipmentCategoryRepository categories, ScopeResolver scopes,
                              DomainEventPublisher events) {
        this.vessels = vessels;
        this.spares = spares;
        this.categories = categories;
        this.scopes = scopes;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VesselRef> vesselByImo(String imoNumber) {
        if (imoNumber == null || imoNumber.isBlank()) return Optional.empty();
        return vessels.findByImoNumber(imoNumber.trim())
                .map(v -> new VesselRef(v.getId(), v.getName(), v.getImoNumber(), v.getOrganizationId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VesselRef> vessel(Long vesselId) {
        if (vesselId == null) return Optional.empty();
        return vessels.findById(vesselId)
                .map(v -> new VesselRef(v.getId(), v.getName(), v.getImoNumber(), v.getOrganizationId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, ExistingSpare> sparesByVmpRef(Long vesselId) {
        Map<Long, String> categoryCodes = new LinkedHashMap<>();
        categories.findAll().forEach(c -> categoryCodes.put(c.getId(), c.getCode()));

        Map<String, ExistingSpare> byRef = new LinkedHashMap<>();
        for (Spare s : spares.findByVesselIdOrderByPathAsc(vesselId)) {
            String ref = s.getVmpRef() == null ? s.getPath() : s.getVmpRef();
            byRef.put(ref, new ExistingSpare(s.getId(), ref, s.getEquipmentCategoryId(),
                    categoryCodes.get(s.getEquipmentCategoryId()), valuesOf(s)));
        }
        return byRef;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> vmpRefs(Long vesselId) {
        return spares.findByVesselIdOrderByPathAsc(vesselId).stream()
                .map(s -> s.getVmpRef() == null ? s.getPath() : s.getVmpRef())
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Long create(Long vesselId, String vmpRef, Long equipmentCategoryId, SpareValues values) {
        Spare spare = new Spare(vesselId, equipmentCategoryId, vmpRef, values.name());
        parentOf(vesselId, vmpRef).ifPresent(parent -> spare.setParentSpareId(parent.getId()));
        apply(spare, values);
        Spare saved = spares.save(spare);
        publishServiceDate(saved, null, values.lastAnnualServiceDate());
        return saved.getId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(Long spareId, Long equipmentCategoryId, SpareValues values) {
        Spare spare = spares.findById(spareId).orElseThrow();
        LocalDate before = spare.getLastAnnualServiceDate();
        if (equipmentCategoryId != null) spare.setEquipmentCategoryId(equipmentCategoryId);
        apply(spare, values);
        spares.save(spare);
        publishServiceDate(spare, before, spare.getLastAnnualServiceDate());
    }

    /** 13.1.2 hangs under 13.1; a top-level reference has no parent. */
    private Optional<Spare> parentOf(Long vesselId, String vmpRef) {
        int lastDot = vmpRef.lastIndexOf('.');
        if (lastDot <= 0) return Optional.empty();
        String parentRef = vmpRef.substring(0, lastDot);
        return spares.findByVesselIdOrderByPathAsc(vesselId).stream()
                .filter(s -> parentRef.equals(s.getVmpRef()) || parentRef.equals(s.getPath()))
                .findFirst();
    }

    private void apply(Spare spare, SpareValues v) {
        if (v.make() != null) spare.setMake(blankToNull(v.make()));
        if (v.model() != null) spare.setModel(blankToNull(v.model()));
        if (v.serialNumber() != null) spare.setSerialNumber(blankToNull(v.serialNumber()));
        if (v.softwareVersion() != null) spare.setSoftwareVersion(blankToNull(v.softwareVersion()));
        if (v.installationDate() != null) spare.setInstallationDate(v.installationDate());
        if (v.expirationDate() != null) spare.setExpirationDate(v.expirationDate());
        if (v.lastAnnualServiceDate() != null) spare.setLastAnnualServiceDate(v.lastAnnualServiceDate());
        if (v.lastSurveyDate() != null) spare.setLastSurveyDate(v.lastSurveyDate());
        if (v.lastAptDate() != null) spare.setLastAptDate(v.lastAptDate());
        if (v.criticality() != null) spare.setCriticality(Criticality.valueOf(v.criticality()));
        if (Boolean.TRUE.equals(v.tracksRunningHours()) && !spare.isTracksRunningHours()) {
            // The meter reading itself stays with the Captain's readings (IMP-12).
            spare.enableRunningHours(spare.getRunningHours());
        }
    }

    private void publishServiceDate(Spare spare, LocalDate before, LocalDate after) {
        if (Objects.equals(before, after) || after == null) return;
        Long organizationId = vessels.findById(spare.getVesselId()).map(Vessel::getOrganizationId).orElse(null);
        AccessScope scope = scopes.currentScope();
        events.publish(new FleetEvents.ServiceDateChanged(spare.getId(), spare.getVesselId(), organizationId,
                before, after, scope.userId(), Instant.now()));
    }

    private static SpareValues valuesOf(Spare s) {
        return new SpareValues(s.getName(), s.getMake(), s.getModel(), s.getSerialNumber(), s.getSoftwareVersion(),
                s.getInstallationDate(), s.getExpirationDate(), s.getLastAnnualServiceDate(),
                s.getLastSurveyDate(), s.getLastAptDate(), s.isTracksRunningHours(), s.getCriticality().name());
    }

    private static String blankToNull(String s) {
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
