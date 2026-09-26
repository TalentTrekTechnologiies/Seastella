package com.seastella.fleet.internal;

import com.seastella.fleet.api.Criticality;
import com.seastella.fleet.internal.FleetSeedContributor.SpareSpec;

import java.util.List;

import static com.seastella.fleet.api.Criticality.CRITICAL;
import static com.seastella.fleet.api.Criticality.HIGH;
import static com.seastella.fleet.api.Criticality.LOW;
import static com.seastella.fleet.api.Criticality.MEDIUM;

/**
 * The standard bridge-equipment fit from SoW section 9.4, in the VMP template's
 * own decimal structure.
 *
 * <p>This is a real merchant-vessel navigational fit, not an invented list, and
 * the nesting is the point: an EPIRB's battery and hydrostatic release are
 * Spares in their own right beneath the EPIRB Spare, and a radar's magnetron
 * and fans sit beneath the radar. Parentage is derived from the decimal path,
 * so {@code 13.1.2} attaches under {@code 13.1} with no separate parent column.
 *
 * <p>Only the magnetrons and their radars accrue running hours - that is how
 * the equipment actually behaves, and it keeps the running-hour dashboards
 * honest rather than uniformly populated.
 */
final class FleetSeedCatalogue {

    private FleetSeedCatalogue() {}

    static final List<SpareSpec> SPARES = List.of(
            new SpareSpec("1.1", "AIS Transponder", "AIS", "Furuno", "FA-170", false, HIGH),

            new SpareSpec("2.1", "VHF No.1", "VHF", "Sailor", "6222", false, CRITICAL),
            new SpareSpec("2.2", "VHF No.2", "VHF", "Sailor", "6222", false, CRITICAL),

            new SpareSpec("3.1", "MF/HF with DSC and NBDP", "MFHF", "Sailor", "6300", false, CRITICAL),

            new SpareSpec("4.1", "SAT-C No.1", "SATC", "Sailor", "6110 mini-C", false, HIGH),
            new SpareSpec("4.2", "SAT-C No.2", "SATC", "Sailor", "6110 mini-C", false, HIGH),

            new SpareSpec("5.1", "LRIT Terminal", "LRIT", "Thrane & Thrane", "TT-3027M", false, MEDIUM),
            new SpareSpec("6.1", "SSAS Unit", "SSAS", "Thrane & Thrane", "TT-3000SSA", false, HIGH),
            new SpareSpec("7.1", "NAVTEX Receiver", "NAVTEX", "Furuno", "NX-700B", false, MEDIUM),

            // EPIRBs carry their battery and hydrostatic release as child spares.
            new SpareSpec("8.1", "EPIRB No.1", "EPIRB", "Jotron", "Tron 60GPS", false, CRITICAL),
            new SpareSpec("8.1.1", "EPIRB No.1 Battery", "EPIRB", "Jotron", "Tron 60 BP", false, CRITICAL),
            new SpareSpec("8.1.2", "EPIRB No.1 Hydrostatic Release", "EPIRB", "Hammar", "H20", false, CRITICAL),
            new SpareSpec("8.2", "EPIRB No.2", "EPIRB", "Jotron", "Tron 60GPS", false, HIGH),
            new SpareSpec("8.2.1", "EPIRB No.2 Battery", "EPIRB", "Jotron", "Tron 60 BP", false, HIGH),
            new SpareSpec("8.2.2", "EPIRB No.2 Hydrostatic Release", "EPIRB", "Hammar", "H20", false, HIGH),

            new SpareSpec("9.1", "SART No.1", "SART", "Jotron", "Tron SART20", false, HIGH),
            new SpareSpec("9.2", "SART No.2", "SART", "Jotron", "Tron SART20", false, HIGH),

            new SpareSpec("10.1", "GMDSS Walkie-Talkie No.1", "GMDSS_WT", "Jotron", "TR-30", false, MEDIUM),
            new SpareSpec("10.2", "GMDSS Walkie-Talkie No.2", "GMDSS_WT", "Jotron", "TR-30", false, MEDIUM),
            new SpareSpec("10.3", "GMDSS Walkie-Talkie No.3", "GMDSS_WT", "Jotron", "TR-30", false, MEDIUM),

            new SpareSpec("11.1", "VDR", "VDR", "Furuno", "VR-7000", false, CRITICAL),
            new SpareSpec("11.1.1", "VDR Backup Battery", "VDR", "Furuno", "VR-BAT-7000", false, HIGH),
            new SpareSpec("11.1.2", "VDR Acoustic Beacon", "VDR", "Dukane", "DK120", false, HIGH),
            new SpareSpec("11.1.3", "VDR FFC Battery", "VDR", "Furuno", "FFC-BAT", false, MEDIUM),
            new SpareSpec("11.1.4", "VDR FFC HRU", "VDR", "Hammar", "H20", false, MEDIUM),

            new SpareSpec("12.1", "Gyro No.1", "GYRO", "Tokyo Keiki", "TG-8000", false, CRITICAL),
            new SpareSpec("12.2", "Gyro No.2", "GYRO", "Tokyo Keiki", "TG-8000", false, HIGH),

            new SpareSpec("13.1", "X-Band Radar", "RADAR", "Furuno", "FAR-2228", true, CRITICAL),
            new SpareSpec("13.1.1", "X-Band Magnetron", "RADAR", "Furuno", "MG5436", true, CRITICAL),
            new SpareSpec("13.1.2", "X-Band Display Fan", "RADAR", "Furuno", "FAN-DC24", false, MEDIUM),
            new SpareSpec("13.1.3", "X-Band Processor Fan", "RADAR", "Furuno", "FAN-DC24", false, MEDIUM),
            new SpareSpec("13.1.4", "X-Band Scanner Unit Fan", "RADAR", "Furuno", "FAN-SC12", false, HIGH),
            new SpareSpec("13.2", "S-Band Radar", "RADAR", "Furuno", "FAR-2338S", true, CRITICAL),
            new SpareSpec("13.2.1", "S-Band Magnetron", "RADAR", "Furuno", "MG5223F", true, CRITICAL),
            new SpareSpec("13.2.2", "S-Band Display Fan", "RADAR", "Furuno", "FAN-DC24", false, MEDIUM),
            new SpareSpec("13.2.3", "S-Band Scanner Unit Fan", "RADAR", "Furuno", "FAN-SC12", false, HIGH),

            new SpareSpec("14.1", "ECDIS No.1", "ECDIS", "Furuno", "FMD-3200", false, CRITICAL),
            new SpareSpec("14.1.1", "ECDIS No.1 Display Fan", "ECDIS", "Furuno", "FAN-DC24", false, HIGH),
            new SpareSpec("14.1.2", "ECDIS No.1 Processor Fan", "ECDIS", "Furuno", "FAN-DC24", false, HIGH),
            new SpareSpec("14.1.3", "ECDIS No.1 SSD Unit", "ECDIS", "Samsung", "MZ-7KE256", false, HIGH),
            new SpareSpec("14.2", "ECDIS No.2", "ECDIS", "Furuno", "FMD-3200", false, CRITICAL),
            new SpareSpec("14.2.1", "ECDIS No.2 Display Fan", "ECDIS", "Furuno", "FAN-DC24", false, MEDIUM),
            new SpareSpec("14.2.2", "ECDIS No.2 Processor Fan", "ECDIS", "Furuno", "FAN-DC24", false, MEDIUM),
            new SpareSpec("14.2.3", "ECDIS No.2 SSD Unit", "ECDIS", "Samsung", "MZ-7KE256", false, MEDIUM),

            new SpareSpec("15.1", "Speed Log", "SPEED_LOG", "Furuno", "DS-80", false, HIGH),
            new SpareSpec("16.1", "Echo Sounder", "ECHO_SOUNDER", "Furuno", "FE-800", false, HIGH),
            new SpareSpec("17.1", "Anemometer", "ANEMOMETER", "Observator", "OMC-139", false, LOW),
            new SpareSpec("18.1", "Autopilot", "AUTOPILOT", "Tokyo Keiki", "PR-9000", false, CRITICAL),
            new SpareSpec("19.1", "BNWAS", "BNWAS", "Daiwa", "DBW-100", false, HIGH),

            new SpareSpec("20.1", "ITU List IV", "ITU_PUB", "ITU", "List IV", false, LOW),
            new SpareSpec("20.2", "GMDSS Manual", "ITU_PUB", "IMO", "GMDSS Manual", false, LOW),
            new SpareSpec("20.3", "ITU List V (Stations & Maritime Mobile)", "ITU_PUB", "ITU", "List V", false, LOW),

            new SpareSpec("21.1", "GPS No.1", "GPS", "Furuno", "GP-170", false, CRITICAL),
            new SpareSpec("21.2", "GPS No.2", "GPS", "Furuno", "GP-170", false, HIGH));
}
