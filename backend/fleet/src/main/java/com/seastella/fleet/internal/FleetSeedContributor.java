package com.seastella.fleet.internal;

import com.seastella.core.api.seed.SeedContext;
import com.seastella.core.api.seed.SeedContributor;
import com.seastella.fleet.api.Criticality;
import com.seastella.fleet.api.SpareStatus;
import com.seastella.fleet.api.VesselStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds organizations, vessels, the equipment-category lookup, the per-vessel
 * Spare tree and replacement-part stock.
 *
 * <p>Two organizations exist on purpose: cross-organization isolation cannot be
 * tested against a single-tenant dataset, and the dashboards' scoping is only
 * meaningful when there is something out of scope to exclude.
 */
@Component
public class FleetSeedContributor implements SeedContributor {

    /** The bridge fit each vessel receives, from SoW s9.4. */
    public record SpareSpec(String path, String name, String categoryCode, String make,
                            String model, boolean runningHours, Criticality criticality) {}

    private final OrganizationRepository organizations;
    private final VesselRepository vessels;
    private final EquipmentCategoryRepository categories;
    private final SpareRepository spares;
    private final ReplacementPartRepository parts;

    FleetSeedContributor(OrganizationRepository organizations, VesselRepository vessels,
                         EquipmentCategoryRepository categories, SpareRepository spares,
                         ReplacementPartRepository parts) {
        this.organizations = organizations;
        this.vessels = vessels;
        this.categories = categories;
        this.spares = spares;
        this.parts = parts;
    }

    @Override public int order() { return 10; }

    @Override public String name() { return "fleet (organizations, vessels, spare trees)"; }

    @Override
    public void contribute(SeedContext ctx) {
        Map<String, Long> categoryIds = seedCategories();

        // --- Organization 1: the primary demo tenant -------------------------
        Long acme = seedOrganization("ACME", "Acme Ship Management Pte Ltd",
                "12 Keppel Road, Singapore 089057", "ops@acme-shipmanagement.example");
        ctx.put("org.acme", acme);

        seedVessel(ctx, "vessel.kestrel", acme, "MV Kestrel Trader", "9412367",
                "563812000", "9V7421", "Singapore", "DNV", "South East Asia",
                "Bulk Carrier", new BigDecimal("81250.00"), VesselStatus.ACTIVE, categoryIds);

        seedVessel(ctx, "vessel.brahmaputra", acme, "MV Brahmaputra", "9523481",
                "419006512", "ATQD6", "India", "IRS", "Arabian Sea",
                "Crude Oil Tanker", new BigDecimal("115400.00"), VesselStatus.ACTIVE, categoryIds);

        seedVessel(ctx, "vessel.coral", acme, "MV Coral Sentinel", "9388125",
                "477995100", "VRQK7", "Hong Kong", "ABS", "North Pacific",
                "Container Ship", new BigDecimal("63900.00"), VesselStatus.ACTIVE, categoryIds);

        // A dry-docked vessel so the status breakdown is not uniformly green.
        seedVessel(ctx, "vessel.sable", acme, "MV Sable Dawn", "9276543",
                "636019842", "D5QT3", "Liberia", "LR", "West Africa",
                "Bulk Carrier", new BigDecimal("76300.00"), VesselStatus.DRY_DOCK, categoryIds);

        // --- Organization 2: exists so isolation has something to exclude ----
        Long nordic = seedOrganization("NORDIC", "Nordic Tanker Operations AS",
                "Stromsveien 96, 0663 Oslo, Norway", "ops@nordic-tanker.example");
        ctx.put("org.nordic", nordic);

        seedVessel(ctx, "vessel.bergen", nordic, "MT Bergen Spirit", "9611204",
                "257845000", "LAQR7", "Norway", "DNV", "North Sea",
                "Product Tanker", new BigDecimal("49800.00"), VesselStatus.ACTIVE, categoryIds);

        seedVessel(ctx, "vessel.fjord", nordic, "MT Fjord Pioneer", "9655118",
                "257913000", "LAWN5", "Norway", "DNV", "Baltic",
                "Chemical Tanker", new BigDecimal("38200.00"), VesselStatus.ACTIVE, categoryIds);
    }

    private Map<String, Long> seedCategories() {
        Map<String, Long> ids = new HashMap<>();
        List<String[]> defs = List.of(
                new String[]{"AIS", "AIS"}, new String[]{"VHF", "VHF"},
                new String[]{"MFHF", "MF/HF with DSC and NBDP"}, new String[]{"SATC", "SAT-C"},
                new String[]{"LRIT", "LRIT"}, new String[]{"SSAS", "SSAS"},
                new String[]{"NAVTEX", "NAVTEX"}, new String[]{"EPIRB", "EPIRB"},
                new String[]{"SART", "SART"}, new String[]{"GMDSS_WT", "GMDSS Walkie-Talkie"},
                new String[]{"VDR", "VDR / SVDR"}, new String[]{"GYRO", "Gyro"},
                new String[]{"RADAR", "Radar"}, new String[]{"ECDIS", "ECDIS"},
                new String[]{"SPEED_LOG", "Speed Log"}, new String[]{"ECHO_SOUNDER", "Echo Sounder"},
                new String[]{"ANEMOMETER", "Anemometer"}, new String[]{"AUTOPILOT", "Autopilot"},
                new String[]{"BNWAS", "BNWAS"}, new String[]{"ITU_PUB", "ITU Publications"},
                new String[]{"GPS", "GPS"});

        int order = 1;
        for (String[] d : defs) {
            Long id = categories.findByCode(d[0])
                    .map(EquipmentCategory::getId)
                    .orElseGet(() -> categories.save(
                            new EquipmentCategory(d[0], d[1], defs.indexOf(d) + 1)).getId());
            ids.put(d[0], id);
            order++;
        }
        return ids;
    }

    private Long seedOrganization(String code, String name, String address, String email) {
        return organizations.findByCode(code).map(Organization::getId).orElseGet(() -> {
            Organization org = new Organization(name, code);
            org.setAddress(address);
            org.setContactEmail(email);
            org.markSeed();
            return organizations.save(org).getId();
        });
    }

    private void seedVessel(SeedContext ctx, String handle, Long orgId, String name, String imo,
                            String mmsi, String callSign, String flag, String vesselClass,
                            String area, String type, BigDecimal dwt, VesselStatus status,
                            Map<String, Long> categoryIds) {

        if (vessels.existsByImoNumber(imo)) {
            vessels.findAll().stream()
                    .filter(v -> imo.equals(v.getImoNumber()))
                    .findFirst()
                    .ifPresent(v -> ctx.put(handle, v.getId()));
            return;
        }

        Vessel vessel = new Vessel(orgId, name, imo);
        vessel.setMmsi(mmsi);
        vessel.setCallSign(callSign);
        vessel.setFlag(flag);
        vessel.setVesselClass(vesselClass);
        vessel.setArea(area);
        vessel.setVesselType(type);
        vessel.setDwt(dwt);
        vessel.setStatus(status);
        vessel.markSeed();

        Long vesselId = vessels.save(vessel).getId();
        ctx.put(handle, vesselId);

        seedSpareTree(ctx, handle, vesselId, categoryIds);
        seedParts(vesselId, handle, ctx);
    }

    /**
     * Builds the recursive spare tree. Parentage comes from the decimal path,
     * so "13.1.2" is attached under "13.1" - the VMP structure reproduced
     * exactly, with no separate parent column in the source data.
     */
    private void seedSpareTree(SeedContext ctx, String vesselHandle, Long vesselId,
                               Map<String, Long> categoryIds) {

        Map<String, Long> byPath = new HashMap<>();
        LocalDate installed = ctx.today().minusYears(4);

        for (SpareSpec spec : FleetSeedCatalogue.SPARES) {
            Long categoryId = categoryIds.get(spec.categoryCode());
            if (categoryId == null) continue;

            Spare spare = new Spare(vesselId, categoryId, spec.path(), spec.name());
            spare.setMake(spec.make());
            spare.setModel(spec.model());
            spare.setSerialNumber(serialFor(vesselId, spec.path()));
            spare.setInstallationDate(installed);
            spare.setCriticality(spec.criticality());
            spare.setStatus(SpareStatus.OPERATIONAL);
            spare.markSeed();

            if (spec.runningHours()) {
                spare.enableRunningHours(new BigDecimal(2000 + (spec.path().hashCode() & 0x0FFF)));
            }

            int lastDot = spec.path().lastIndexOf('.');
            if (lastDot > 0) {
                String parentPath = spec.path().substring(0, lastDot);
                Long parentId = byPath.get(parentPath);
                // Parent must exist and be on this same vessel; the composite
                // foreign key would reject anything else.
                if (parentId != null) {
                    spare.setParentSpareId(parentId);
                }
            }

            Long id = spares.save(spare).getId();
            byPath.put(spec.path(), id);
            ctx.put("spare." + vesselHandle.substring("vessel.".length()) + "." + spec.path(), id);
        }
    }

    /** Replacement-part stock, with deliberate shortages on two vessels. */
    private void seedParts(Long vesselId, String vesselHandle, SeedContext ctx) {
        boolean shortVessel = vesselHandle.equals("vessel.kestrel")
                || vesselHandle.equals("vessel.brahmaputra");

        record PartSpec(String name, String partNo, String maker, int onHand, int min, String loc) {}

        List<PartSpec> specs = List.of(
                new PartSpec("Magnetron MG5436 (X-Band)", "MG5436", "Furuno",
                        shortVessel ? 0 : 2, 1, "Bridge store, drawer 3"),
                new PartSpec("Cooling fan DC24V", "FAN-DC24", "Furuno",
                        shortVessel ? 1 : 6, 4, "Bridge store, drawer 1"),
                new PartSpec("ECDIS SSD 256GB", "MZ-7KE256", "Samsung",
                        2, 1, "Bridge store, drawer 2"),
                new PartSpec("EPIRB battery pack", "TRON-60-BP", "Jotron",
                        shortVessel ? 0 : 2, 1, "GMDSS locker"),
                new PartSpec("Gyro fuse set 5A", "FUSE-5A-10", "Tokyo Keiki",
                        12, 5, "Bridge store, drawer 4"),
                new PartSpec("VDR backup battery", "VR-BAT-7000", "Furuno",
                        1, 1, "VDR cabinet"));

        for (PartSpec s : specs) {
            ReplacementPart part = new ReplacementPart(vesselId, s.name(), s.onHand(), s.min());
            part.setPartNumber(s.partNo());
            part.setManufacturer(s.maker());
            part.setLocation(s.loc());
            part.markSeed();
            parts.save(part);
        }
    }

    private static String serialFor(Long vesselId, String path) {
        return "SN-" + vesselId + "-" + path.replace('.', '-');
    }
}
