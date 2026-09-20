package com.seastella.troubleshooting.internal;

import com.seastella.core.api.seed.SeedContext;
import com.seastella.core.api.seed.SeedContributor;
import com.seastella.fleet.api.FleetDirectory;
import com.seastella.servicerequest.api.ProblemTypeCatalog;
import com.seastella.troubleshooting.api.TroubleshootingOutcome;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <b>Sample</b> guided checks for the demo (OI-05).
 *
 * <p>SoW s16 makes the troubleshooting content Seastella's to supply. Until it
 * arrives, these flows show the assistant working on the problem types the
 * open item names - Radar, ECDIS, GPS, VHF, AIS - plus a general fallback for
 * any other equipment. They are ordinary, maker-neutral first checks a bridge
 * team would make, every flow is stored as {@code SAMPLE}, and the screen says
 * so. They are seed data: they never reach a production database.
 */
@Component
public class TroubleshootingSeedContributor implements SeedContributor {

    private static final String RESOLVED = "=RESOLVED";
    private static final String TEMP_FIX = "=TEMPORARY_FIX";
    private static final String UNRESOLVED = "=UNRESOLVED";

    private final TroubleshootingFlowRepository flows;
    private final TroubleshootingStepRepository steps;
    private final FleetDirectory fleet;
    private final ProblemTypeCatalog problemTypes;

    TroubleshootingSeedContributor(TroubleshootingFlowRepository flows, TroubleshootingStepRepository steps,
                                   FleetDirectory fleet, ProblemTypeCatalog problemTypes) {
        this.flows = flows;
        this.steps = steps;
        this.fleet = fleet;
        this.problemTypes = problemTypes;
    }

    /** After problem types (service-request, 40). */
    @Override public int order() { return 45; }

    @Override public String name() { return "troubleshooting (sample guided checks)"; }

    private record S(String key, String prompt, String help, String yes, String no) {}

    @Override
    public void contribute(SeedContext ctx) {
        flow("RADAR", "RADAR_NO_ECHO", "SAMPLE-RADAR-NO-ECHO", "Radar: no echoes or weak picture", List.of(
                new S("transmit", "Is the radar in TRANSMIT, with its warm-up period finished?",
                        "Most radars need a few minutes of warm-up before TRANSMIT is available.",
                        "controls", "transmit_now"),
                new S("transmit_now", "Switch to TRANSMIT once warm-up ends. Do echoes appear now?", null,
                        RESOLVED, "controls"),
                new S("controls", "Set gain, sea clutter and rain clutter to their automatic or default settings. Do echoes appear now?",
                        null, RESOLVED, "tuning"),
                new S("tuning", "Does the tuning indicator reach its normal level?",
                        "A low tuning level with no echoes often points to the magnetron or the receiver.",
                        "scanner", "magnetron"),
                new S("magnetron", "Are the magnetron's running hours past the maker's recommended replacement interval?",
                        "The hours recorded for this vessel's magnetron are on the vessel page.",
                        UNRESOLVED, "scanner"),
                new S("scanner", "Is the scanner (antenna) rotating normally?", null,
                        "restart", UNRESOLVED),
                new S("restart", "Power the radar off completely, wait one minute and restart. Is the picture normal after warm-up?",
                        null, TEMP_FIX, UNRESOLVED)));

        flow("ECDIS", "ECDIS_NO_GPS_INPUT", "SAMPLE-ECDIS-NO-POSITION", "ECDIS: no GPS input / position lost", List.of(
                new S("gps_fix", "Does the GPS receiver's own display show a valid position fix?", null,
                        "ecdis_input", "gps_power"),
                new S("gps_power", "Is the GPS receiver powered, with its antenna cable connected and undamaged?", null,
                        UNRESOLVED, "gps_restore"),
                new S("gps_restore", "Restore power or reconnect the antenna cable. Does the GPS show a fix within a few minutes?",
                        null, "ecdis_input", UNRESOLVED),
                new S("ecdis_input", "In the ECDIS sensor settings, is the GPS position input selected and receiving data?",
                        null, "cable", "select_input"),
                new S("select_input", "Select the correct GPS input. Is the position restored on the ECDIS?", null,
                        RESOLVED, "cable"),
                new S("cable", "Is the data cable between the GPS and the ECDIS firmly connected at both ends?", null,
                        "second_gps", "reseat"),
                new S("reseat", "Reconnect the data cable. Is the position restored?", null,
                        RESOLVED, "second_gps"),
                new S("second_gps", "If a second GPS is fitted, does selecting it give a valid position on the ECDIS?",
                        "Note any error code shown; you can record it when you finish.",
                        TEMP_FIX, UNRESOLVED)));

        flow("GPS", "GPS_NO_FIX", "SAMPLE-GPS-NO-FIX", "GPS: no position fix", List.of(
                new S("cable", "Is the antenna cable connected at the receiver and free of visible damage?", null,
                        "sky_view", "reconnect"),
                new S("reconnect", "Reconnect or secure the antenna cable. Does the receiver get a fix within ten minutes?",
                        null, RESOLVED, "sky_view"),
                new S("sky_view", "Does the antenna still have a clear view of the sky, with nothing new blocking it?", null,
                        "restart", UNRESOLVED),
                new S("restart", "Restart the receiver. Does it get a fix within ten minutes?", null,
                        TEMP_FIX, UNRESOLVED)));

        flow("VHF", "VHF_NO_TRANSMIT", "SAMPLE-VHF-NO-TRANSMIT", "VHF: unable to transmit", List.of(
                new S("mode", "Is the set on a normal working channel at high power, not in a low-power or receive-only mode?",
                        null, "antenna", "fix_mode"),
                new S("fix_mode", "Select a working channel at high power. Does a radio check succeed now?", null,
                        RESOLVED, "antenna"),
                new S("antenna", "Is the antenna cable connected at the radio, with the antenna undamaged?", null,
                        "other_set", UNRESOLVED),
                new S("other_set", "Does a radio check succeed on the second VHF?",
                        "If so, bridge communications can continue on it while this set is serviced.",
                        TEMP_FIX, UNRESOLVED)));

        flow("AIS", "AIS_NOT_TRANSMITTING", "SAMPLE-AIS-NOT-TRANSMITTING", "AIS: own ship not transmitting", List.of(
                new S("alarm", "Does the AIS show an active alarm, or a silent / transmitter-off mode?", null,
                        "silent", "position"),
                new S("silent", "Clear silent mode and acknowledge the alarm. Is own ship transmitting now?",
                        "Confirm with a nearby station or an AIS monitoring service.",
                        RESOLVED, "position"),
                new S("position", "Is the AIS receiving a valid position from its GPS?", null,
                        "restart", UNRESOLVED),
                new S("restart", "Restart the AIS unit. Is own ship transmitting after a few minutes?", null,
                        TEMP_FIX, UNRESOLVED)));

        flow(null, null, "SAMPLE-GENERAL", "General equipment checks", List.of(
                new S("power", "Is the equipment powered, with its breaker and fuse intact?", null,
                        "error_code", "restore_power"),
                new S("restore_power", "Reset the breaker or replace the fuse from the ship's spares. Does the equipment work normally now?",
                        "A breaker or fuse that trips again points to a fault that needs service.",
                        TEMP_FIX, UNRESOLVED),
                new S("error_code", "Is an alarm or error code shown?",
                        "Note it exactly; you can record it when you finish.",
                        "restart", "restart"),
                new S("restart", "Restart the equipment as its manual describes. Does it work normally now?", null,
                        TEMP_FIX, UNRESOLVED)));
    }

    private void flow(String categoryCode, String problemCode, String code, String name, List<S> definition) {
        if (flows.existsByCode(code)) return;

        Long categoryId = categoryCode == null ? null : fleet.equipmentCategoryIdByCode(categoryCode).orElse(null);
        if (categoryCode != null && categoryId == null) return;
        Long problemTypeId = problemCode == null ? null
                : problemTypes.findByCode(problemCode).map(ProblemTypeCatalog.ProblemTypeRef::id).orElse(null);
        if (problemCode != null && problemTypeId == null) return;

        TroubleshootingFlow flow = flows.save(new TroubleshootingFlow(categoryId, problemTypeId, code, name, 1,
                TroubleshootingFlow.SAMPLE, definition.get(0).key()));

        int order = 1;
        for (S s : definition) {
            steps.save(new TroubleshootingStep(flow.getId(), s.key(), order++, s.prompt(), s.help(),
                    nextKey(s.yes()), outcome(s.yes()), nextKey(s.no()), outcome(s.no())));
        }
        flow.publish(null, java.time.Instant.now());
        flows.save(flow);
    }

    private static String nextKey(String target) {
        return target.startsWith("=") ? null : target;
    }

    private static TroubleshootingOutcome outcome(String target) {
        return target.startsWith("=") ? TroubleshootingOutcome.valueOf(target.substring(1)) : null;
    }
}
