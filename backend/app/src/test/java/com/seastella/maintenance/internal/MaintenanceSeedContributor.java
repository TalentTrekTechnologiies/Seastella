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
     * zero is due today. The spread deliberately covers each band boundary
     * named in SoW s7, including the 9/10-day edge raised as OI-02.
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

    /** Platform defaults matching the SoW table; organizations may override. */
    private void seedThresholds() {
        if (!thresholds.findAll().isEmpty()) {
            return;
        }
        thresholds.save(new MaintenanceThreshold(null, "URGENT", 1, 9));
        thresholds.save(new MaintenanceThreshold(null, "APPROACHING", 10, 15));
    }
}
