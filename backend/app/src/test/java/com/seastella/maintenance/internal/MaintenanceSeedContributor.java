package com.seastella.maintenance.internal;

import com.seastella.core.api.seed.SeedContext;
import com.seastella.core.api.seed.SeedContributor;
import com.seastella.fleet.api.FleetDirectory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Seeds the colour-band thresholds and per-spare maintenance rules.
 *
 * <p>Due dates are chosen so the fleet lands in <em>every</em> band at once -
 * normal, approaching, urgent, due today and overdue. A demo dataset where
 * everything is green proves nothing about the dashboards, and one where
 * everything is red is equally useless.
 */
@Component
public class MaintenanceSeedContributor implements SeedContributor {

    /**
     * Offsets from today, in days, cycled across spares. Negative is overdue,
     * zero is due today. The spread deliberately lands in every band of the
     * client's ladder - red to 15, yellow 16 to 60, green beyond - and on both
     * sides of each boundary, so a band that stopped being reachable shows up
     * as an empty count rather than as a screen that merely looks plausible.
     */
    private static final int[] DUE_OFFSETS = {
            -34, -12, -3, 0, 2, 5, 9, 10, 12, 15, 18, 24, 41, 63, 95, 128, 174, 210
    };

    private final SpareMaintenanceRuleRepository rules;
    private final MaintenanceThresholdRepository thresholds;
    private final FleetDirectory fleet;

    MaintenanceSeedContributor(SpareMaintenanceRuleRepository rules,
                               MaintenanceThresholdRepository thresholds,
                               FleetDirectory fleet) {
        this.rules = rules;
        this.thresholds = thresholds;
        this.fleet = fleet;
    }

    @Override public int order() { return 30; }

    @Override public String name() { return "maintenance (thresholds, due dates across every band)"; }

    @Override
    public void contribute(SeedContext ctx) {
        seedThresholds();

        LocalDate today = ctx.today();
        List<String> vesselHandles = List.of(
                "vessel.kestrel", "vessel.brahmaputra", "vessel.coral",
                "vessel.sable", "vessel.bergen", "vessel.fjord");

        int offsetCursor = 0;

        for (String handle : vesselHandles) {
            if (!ctx.has(handle)) continue;
            Long vesselId = ctx.id(handle);

            for (Map.Entry<Long, Boolean> entry : fleet.spareIdsWithHourTracking(vesselId).entrySet()) {
                Long spareId = entry.getKey();
                boolean tracksHours = entry.getValue();

                int offset = DUE_OFFSETS[offsetCursor % DUE_OFFSETS.length];
                offsetCursor++;

                // Annual service, 365 days: last service back-dated so that
                // next due lands on the intended offset.
                LocalDate nextDue = today.plusDays(offset);
                LocalDate lastService = nextDue.minusDays(365);

                SpareMaintenanceRule calendar =
                        SpareMaintenanceRule.calendar(spareId, vesselId, 365, lastService);
                rules.save(calendar);

                // The same date has to land on the spare itself, or the demo
                // contradicts itself: the row would show a due date while its
                // own line read "Last annual service not recorded". In the
                // running platform that pairing cannot happen - entering the
                // date on the spare is what creates this rule - so a seed that
                // writes only the rule shows a state the product never
                // produces. fleet owns the spare, so the date is handed over
                // rather than written here (see FleetServiceDateSeedContributor).
                ctx.put("service." + spareId, lastService.toEpochDay());

                // Magnetrons additionally carry a running-hour rule, which is
                // how they are actually managed aboard.
                if (tracksHours) {
                    rules.save(SpareMaintenanceRule.runningHours(
                            spareId, vesselId,
                            new BigDecimal("5000.00"),
                            new BigDecimal("1800.00")));
                }
            }
        }
    }

    /**
     * Platform defaults, should the seed ever run before the migrations that
     * write them (V34, then V36). Normally a no-op: the rows are reference
     * data and ship with the schema. Kept in step with V36 so the two cannot
     * disagree about what a fresh database starts with.
     */
    private void seedThresholds() {
        if (!thresholds.findAll().isEmpty()) {
            return;
        }
        thresholds.save(new MaintenanceThreshold(null, "URGENT", 1, 15));
        thresholds.save(new MaintenanceThreshold(null, "APPROACHING", 16, 60));
    }
}
