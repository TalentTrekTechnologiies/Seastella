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

import java.math.BigDecimal;
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
    private final ReplacementPartRepository parts;
    private final ScopeResolver scopes;
    private final DomainEventPublisher events;

    DefaultSpareImportGateway(VesselRepository vessels, SpareRepository spares,
                              EquipmentCategoryRepository categories, ReplacementPartRepository parts,
                              ScopeResolver scopes, DomainEventPublisher events) {
        this.vessels = vessels;
        this.spares = spares;
        this.categories = categories;
        this.parts = parts;
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

    // ------------------------------------------------------- vessel particulars

    @Override
    @Transactional(readOnly = true)
    public VesselParticulars particulars(Long vesselId) {
        return vessels.findById(vesselId)
                .map(v -> new VesselParticulars(v.getName(), v.getMmsi(), v.getCallSign(), v.getFlag(),
                        v.getVesselClass(), v.getArea(), v.getVesselType(), v.getDwt()))
                .orElseGet(VesselParticulars::empty);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateVessel(Long vesselId, VesselParticulars v) {
        Vessel vessel = vessels.findById(vesselId).orElseThrow();
        if (v.name() != null) vessel.setName(v.name().trim());
        if (v.mmsi() != null) vessel.setMmsi(blankToNull(v.mmsi()));
        if (v.callSign() != null) vessel.setCallSign(blankToNull(v.callSign()));
        if (v.flag() != null) vessel.setFlag(blankToNull(v.flag()));
        if (v.vesselClass() != null) vessel.setVesselClass(blankToNull(v.vesselClass()));
        if (v.area() != null) vessel.setArea(blankToNull(v.area()));
        if (v.vesselType() != null) vessel.setVesselType(blankToNull(v.vesselType()));
        if (v.dwt() != null) vessel.setDwt(v.dwt());
        vessels.save(vessel);
    }

    // ------------------------------------------------------------ critical spares

    @Override
    @Transactional(readOnly = true)
    public Map<String, ExistingPart> criticalSparesByKey(Long vesselId) {
        Map<Long, String> equipmentNames = new LinkedHashMap<>();
        spares.findByVesselIdOrderByPathAsc(vesselId)
                .forEach(sp -> equipmentNames.put(sp.getId(), sp.getName()));

        Map<String, ExistingPart> byKey = new LinkedHashMap<>();
        for (ReplacementPart part : parts.findByVesselIdOrderByNameAsc(vesselId)) {
            PartValues values = valuesOf(part, equipmentNames.get(part.getSpareId()));
            // First one wins: two rows sharing a key are the caller's duplicate to report,
            // not ours to silently merge.
            byKey.putIfAbsent(values.key(), new ExistingPart(part.getId(), values));
        }
        return byKey;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Long createCriticalSpare(Long vesselId, PartValues v) {
        // A minimum of 0 is what the form says when it states no countable figure;
        // the note carries the words.
        ReplacementPart part = new ReplacementPart(vesselId, v.name().trim(),
                v.quantityOnHand() == null ? 0 : v.quantityOnHand(),
                v.minimumQuantity() == null ? 0 : v.minimumQuantity());
        part.setCritical(true);
        applyPart(part, v);
        return parts.save(part).getId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateCriticalSpare(Long partId, PartValues v) {
        ReplacementPart part = parts.findById(partId).orElseThrow();
        if (v.minimumQuantity() != null) part.setMinimumQuantity(v.minimumQuantity());
        if (v.quantityOnHand() != null) part.setQuantityOnHand(v.quantityOnHand());
        part.setCritical(true);
        applyPart(part, v);
        parts.save(part);
    }

    /**
     * The fields a sheet may set. Stock on hand and the minimum are handled by
     * the callers above, because creating needs a figure and updating must not
     * reset one to zero just because the cell was blank.
     */
    private void applyPart(ReplacementPart part, PartValues v) {
        if (v.spareId() != null) part.setSpareId(v.spareId());
        if (v.minimumNote() != null) part.setMinimumNote(blankToNull(v.minimumNote()));
        if (v.compliance() != null) part.setCompliance(blankToNull(v.compliance()));
        if (v.remarks() != null) part.setRemarks(blankToNull(v.remarks()));
    }

    private static PartValues valuesOf(ReplacementPart p, String equipmentName) {
        return new PartValues(p.getName(), equipmentName, null, p.getSpareId(),
                p.getMinimumQuantity(), p.getMinimumNote(), p.getQuantityOnHand(),
                p.getCompliance(), p.getRemarks());
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
