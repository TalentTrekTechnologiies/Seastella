package com.seastella.fleet.internal;

import com.seastella.fleet.api.Criticality;

import java.util.List;

import static com.seastella.fleet.api.Criticality.CRITICAL;
import static com.seastella.fleet.api.Criticality.HIGH;
import static com.seastella.fleet.api.Criticality.LOW;
import static com.seastella.fleet.api.Criticality.MEDIUM;

/**
 * The standard bridge-equipment fit from SoW section 9.4, in the VMP template's
 * own decimal structure. A new vessel starts with it unless its own equipment
 * list is imported instead.
 *
 * <p>Structure only: the nesting is the point. An EPIRB's battery and
 * hydrostatic release are Spares in their own right beneath the EPIRB Spare,
 * and a radar's magnetron and fans sit beneath the radar. Parentage is derived
 * from the decimal path, so {@code 13.1.2} attaches under {@code 13.1} with no
 * separate parent column. Make, model and serial number are each vessel's own
 * facts and are never filled in here.
 *
 * <p>Only the radars and their magnetrons accrue running hours, which is how
 * the equipment actually behaves.
 */
final class StandardBridgeFit {

    record Item(String path, String name, String categoryCode, boolean runningHours, Criticality criticality) {}

    private StandardBridgeFit() {}

    static final List<Item> ITEMS = List.of(
            new Item("1.1", "AIS Transponder", "AIS", false, HIGH),

            new Item("2.1", "VHF No.1", "VHF", false, CRITICAL),
            new Item("2.2", "VHF No.2", "VHF", false, CRITICAL),

            new Item("3.1", "MF/HF with DSC and NBDP", "MFHF", false, CRITICAL),

            new Item("4.1", "SAT-C No.1", "SATC", false, HIGH),
            new Item("4.2", "SAT-C No.2", "SATC", false, HIGH),

            new Item("5.1", "LRIT Terminal", "LRIT", false, MEDIUM),
            new Item("6.1", "SSAS Unit", "SSAS", false, HIGH),
            new Item("7.1", "NAVTEX Receiver", "NAVTEX", false, MEDIUM),

            // EPIRBs carry their battery and hydrostatic release as child spares.
            new Item("8.1", "EPIRB No.1", "EPIRB", false, CRITICAL),
            new Item("8.1.1", "EPIRB No.1 Battery", "EPIRB", false, CRITICAL),
            new Item("8.1.2", "EPIRB No.1 Hydrostatic Release", "EPIRB", false, CRITICAL),
            new Item("8.2", "EPIRB No.2", "EPIRB", false, HIGH),
            new Item("8.2.1", "EPIRB No.2 Battery", "EPIRB", false, HIGH),
            new Item("8.2.2", "EPIRB No.2 Hydrostatic Release", "EPIRB", false, HIGH),

            new Item("9.1", "SART No.1", "SART", false, HIGH),
            new Item("9.2", "SART No.2", "SART", false, HIGH),

            new Item("10.1", "GMDSS Walkie-Talkie No.1", "GMDSS_WT", false, MEDIUM),
            new Item("10.2", "GMDSS Walkie-Talkie No.2", "GMDSS_WT", false, MEDIUM),
            new Item("10.3", "GMDSS Walkie-Talkie No.3", "GMDSS_WT", false, MEDIUM),

            new Item("11.1", "VDR", "VDR", false, CRITICAL),
            new Item("11.1.1", "VDR Backup Battery", "VDR", false, HIGH),
            new Item("11.1.2", "VDR Acoustic Beacon", "VDR", false, HIGH),
            new Item("11.1.3", "VDR FFC Battery", "VDR", false, MEDIUM),
            new Item("11.1.4", "VDR FFC HRU", "VDR", false, MEDIUM),

            new Item("12.1", "Gyro No.1", "GYRO", false, CRITICAL),
            new Item("12.2", "Gyro No.2", "GYRO", false, HIGH),

            new Item("13.1", "X-Band Radar", "RADAR", true, CRITICAL),
            new Item("13.1.1", "X-Band Magnetron", "RADAR", true, CRITICAL),
            new Item("13.1.2", "X-Band Display Fan", "RADAR", false, MEDIUM),
            new Item("13.1.3", "X-Band Processor Fan", "RADAR", false, MEDIUM),
            new Item("13.1.4", "X-Band Scanner Unit Fan", "RADAR", false, HIGH),
            new Item("13.2", "S-Band Radar", "RADAR", true, CRITICAL),
            new Item("13.2.1", "S-Band Magnetron", "RADAR", true, CRITICAL),
            new Item("13.2.2", "S-Band Display Fan", "RADAR", false, MEDIUM),
            new Item("13.2.3", "S-Band Scanner Unit Fan", "RADAR", false, HIGH),

            new Item("14.1", "ECDIS No.1", "ECDIS", false, CRITICAL),
            new Item("14.1.1", "ECDIS No.1 Display Fan", "ECDIS", false, HIGH),
            new Item("14.1.2", "ECDIS No.1 Processor Fan", "ECDIS", false, HIGH),
            new Item("14.1.3", "ECDIS No.1 SSD Unit", "ECDIS", false, HIGH),
            new Item("14.2", "ECDIS No.2", "ECDIS", false, CRITICAL),
            new Item("14.2.1", "ECDIS No.2 Display Fan", "ECDIS", false, MEDIUM),
            new Item("14.2.2", "ECDIS No.2 Processor Fan", "ECDIS", false, MEDIUM),
            new Item("14.2.3", "ECDIS No.2 SSD Unit", "ECDIS", false, MEDIUM),

            new Item("15.1", "Speed Log", "SPEED_LOG", false, HIGH),
            new Item("16.1", "Echo Sounder", "ECHO_SOUNDER", false, HIGH),
            new Item("17.1", "Anemometer", "ANEMOMETER", false, LOW),
            new Item("18.1", "Autopilot", "AUTOPILOT", false, CRITICAL),
            new Item("19.1", "BNWAS", "BNWAS", false, HIGH),

            new Item("20.1", "ITU List IV", "ITU_PUB", false, LOW),
            new Item("20.2", "GMDSS Manual", "ITU_PUB", false, LOW),
            new Item("20.3", "ITU List V (Stations & Maritime Mobile)", "ITU_PUB", false, LOW),

            new Item("21.1", "GPS No.1", "GPS", false, CRITICAL),
            new Item("21.2", "GPS No.2", "GPS", false, HIGH));
}
