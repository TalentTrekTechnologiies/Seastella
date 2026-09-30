package com.seastella.fleet.internal;

import com.seastella.core.api.seed.SeedContext;
import com.seastella.core.api.seed.SeedContributor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two facts a demo vessel needs before its equipment list reads correctly:
 * the last annual service date on each tracked unit, and a software baseline
 * for the models that carry software.
 *
 * <h2>Why the service date is written here and not with the rule</h2>
 * The maintenance seed creates the calendar rule that produces a due date, but
 * the date the rule was started from belongs to the spare, and {@code fleet}
 * owns the spare. Without this step a demo row shows "due 15 Nov 2026" beside
 * its own line reading "Last annual service not recorded" - a pairing the
 * running platform cannot produce, because entering the date on the spare is
 * precisely what creates the rule. A demo that shows an impossible state costs
 * more than it saves: the first question it draws is about the bug that is not
 * there. The maintenance seed hands the dates over through
 * {@link SeedContext} so neither module reaches into the other.
 *
 * <h2>Why baselines are seeded at all</h2>
 * Software currency is a comparison, and a comparison needs both sides. With
 * no baseline every unit reports "Not known", which looks like a broken column
 * rather than an empty master sheet. These rows give the column something true
 * to say: some models current, some a release behind, one running a build
 * newer than the sheet knows about.
 */
@Component
public class EquipmentDetailSeedContributor implements SeedContributor {

    /** Runs after the maintenance seed (30), which supplies the dates. */
    @Override public int order() { return 35; }

    @Override public String name() { return "equipment detail (service dates, software baselines)"; }

    /**
     * What each manufacturer's fleet is running, as a release a step behind,
     * the current one, and one ahead of the sheet. Keyed by make, because a
     * baseline belongs to a model and every model of one make on a demo vessel
     * may as well move together.
     */
    private record Versions(String behind, String current, String ahead) {}

    private static final Map<String, Versions> BY_MAKE = Map.of(
            "Furuno", new Versions("5.4", "5.6", "5.7-rc1"),
            "Sailor", new Versions("2.3.0", "2.4.1", "2.5.0"),
            "Jotron", new Versions("3.1", "3.2", "3.3"),
            "JRC", new Versions("1.7", "1.8", "1.9"),
            "Kongsberg", new Versions("4.0", "4.2", "4.3"));

    private static final Versions DEFAULT_VERSIONS = new Versions("1.0", "1.2", "1.3");

    private final SpareRepository spares;
    private final SoftwareBaselineRepository baselines;

    EquipmentDetailSeedContributor(SpareRepository spares, SoftwareBaselineRepository baselines) {
        this.spares = spares;
        this.baselines = baselines;
    }

    @Override
    public void contribute(SeedContext ctx) {
        List<Spare> all = spares.findAll();
        applyServiceDates(ctx, all);
        applySoftware(all);
        spares.saveAll(all);
    }

    /** Puts the maintenance seed's start dates onto the spares they belong to. */
    private void applyServiceDates(SeedContext ctx, List<Spare> all) {
        Map<Long, LocalDate> bySpare = new HashMap<>();
        ctx.all().forEach((handle, value) -> {
            if (handle.startsWith("service.") && value != null) {
                bySpare.put(Long.valueOf(handle.substring("service.".length())),
                        LocalDate.ofEpochDay(value));
            }
        });

        for (Spare spare : all) {
            LocalDate date = bySpare.get(spare.getId());
            if (date != null) {
                spare.setLastAnnualServiceDate(date);
            }
        }
    }

    /**
     * Gives the units that carry software a version, and the models they are
     * measured against a baseline.
     *
     * <p>The mix is chosen to look like a fleet rather than like a test: about
     * half the units current, a fifth a release behind, a fifth with nothing
     * recorded, and <b>one in ten</b> running something newer than the sheet.
     * That last state is deliberately rare. It means the master sheet is stale,
     * not that the vessel is at fault, and a demo where a fifth of the fleet is
     * "newer than sheet" reads as a broken column - it is the first thing
     * anybody asks about. Not every box aboard runs firmware anybody records
     * either, so the blanks are part of the picture.
     */
    private void applySoftware(List<Spare> all) {
        Map<String, String> latestByModel = new LinkedHashMap<>();
        int cursor = 0;

        for (Spare spare : all) {
            if (spare.getMake() == null || spare.getModel() == null) continue;

            Versions v = BY_MAKE.getOrDefault(spare.getMake(), DEFAULT_VERSIONS);
            latestByModel.putIfAbsent(spare.getMake() + "\u0000" + spare.getModel(), v.current());

            switch (cursor % 10) {
                case 1, 5 -> spare.setSoftwareVersion(v.behind());
                case 3, 9 -> spare.setSoftwareVersion(null);
                case 7 -> spare.setSoftwareVersion(v.ahead());
                default -> spare.setSoftwareVersion(v.current());
            }
            cursor++;
        }

        latestByModel.forEach((key, latest) -> {
            String[] parts = key.split("\u0000", 2);
            String make = parts[0];
            String model = parts[1];
            // The sheet is uploaded once for the platform, not per vessel, so a
            // model already carrying a baseline is left exactly as it is.
            if (baselines.findByMatchKey(SoftwareMatchKey.of(make, model)).isPresent()) return;
            baselines.save(new SoftwareBaseline(make, model, null, latest,
                    SoftwareBaseline.Source.IMPORTED));
        });
    }
}
