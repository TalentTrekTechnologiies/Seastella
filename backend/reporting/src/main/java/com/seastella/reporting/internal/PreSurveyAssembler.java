package com.seastella.reporting.internal;

import com.seastella.fleet.api.VesselRecords.EquipmentRecord;
import com.seastella.fleet.api.VesselRecords.VesselParticulars;
import com.seastella.maintenance.api.MaintenanceMetrics.DueItem;
import com.seastella.servicerequest.api.ServiceRequestMetrics.RequestSummary;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Fills the QM-F11 form from a vessel's records: every field the form has, by
 * the form's own field name, with a label a person can review.
 *
 * <p>Equipment is found by what it is, not only by its VMP number. Vessels
 * imported from the client's sheets number the VDR "11" with its batteries at
 * 11.1-11.4; vessels built from the standard fit have it at 11.1 with
 * 11.1.1-11.1.4. Matching the item by name within its block, and its battery,
 * HRU, magnetron or SSD as something filed under it, reads both the same.
 *
 * <p>Nothing here is invented. A value the records do not have is left blank
 * and flagged, so the reviewer sees it before the form goes to the vessel.
 */
final class PreSurveyAssembler {

    static final List<String> STATUS_OPTIONS = List.of("Good", "Not Good");
    static final List<String> BAND_OPTIONS = List.of("X-Band", "S-Band", "N/A");
    private static final int MAX_ISSUES = 3;

    record Cell(String key, String label, String value, boolean flagWhenEmpty, List<String> options) {
        boolean missing() { return flagWhenEmpty && (value == null || value.isBlank()); }
    }

    record Row(String label, List<Cell> cells) {}

    /** One box of the form. {@code status} and {@code error} are its "Good / Error (IF any)" line, if it has one. */
    record Section(String title, Cell status, Cell error, List<Row> rows) {}

    record Draft(List<Section> sections) {
        /** Every field, by the form's own name. */
        Map<String, String> values() {
            Map<String, String> out = new LinkedHashMap<>();
            for (Section s : sections) {
                if (s.status() != null) out.put(s.status().key(), s.status().value());
                if (s.error() != null) out.put(s.error().key(), s.error().value());
                for (Row r : s.rows()) for (Cell c : r.cells()) out.put(c.key(), c.value());
            }
            return out;
        }

        List<Cell> cells() {
            List<Cell> out = new ArrayList<>();
            for (Section s : sections) {
                if (s.status() != null) out.add(s.status());
                if (s.error() != null) out.add(s.error());
                for (Row r : s.rows()) out.addAll(r.cells());
            }
            return out;
        }
    }

    private final List<EquipmentRecord> register;
    private final List<DueItem> overdue;
    private final List<RequestSummary> openRequests;
    private final LocalDate today;
    private final DateTimeFormatter dates;

    PreSurveyAssembler(List<EquipmentRecord> register, List<DueItem> overdue, List<RequestSummary> openRequests,
                       LocalDate today, DateTimeFormatter dates) {
        this.register = register;
        this.overdue = overdue;
        this.openRequests = openRequests;
        this.today = today;
        this.dates = dates;
    }

    Draft assemble(VesselParticulars v) {
        List<Section> sections = new ArrayList<>();

        sections.add(new Section("Vessel", null, null, List.of(new Row("", List.of(
                cell("Vessels NameRow1", "Vessel's Name", v.name()),
                cell("IMO NumberRow1", "IMO Number", v.imoNumber()),
                cell("FlagRow1", "Flag", v.flag()),
                cell("ClassRow1", "Class", v.vesselClass()))))));

        // ---- EPIRB, SART, walkie-talkies
        List<Row> lifesaving = new ArrayList<>();
        List<EquipmentRecord> epirbs = units(named("EPIRB").and(not(part())));
        for (int i = 0; i < 2; i++) {
            EquipmentRecord u = at(epirbs, i);
            String n = String.valueOf(i + 1);
            lifesaving.add(new Row("EPIRB No. 0" + n, List.of(
                    cell("MakeEPIRB No " + n, "Make", u == null ? null : u.make()),
                    cell("ModelEPIRB No " + n, "Model", u == null ? null : u.model()),
                    cell("Date8_af_date." + i + ".0", "Battery", date(expiry(under(u, named("BATT"))))),
                    cell("Date8_af_date." + i + ".1", "HRU", date(expiry(under(u, named("HRU", "HYDRO"))))))));
        }
        List<EquipmentRecord> sarts = units(named("SART").and(not(part())));
        for (int i = 0; i < 2; i++) {
            EquipmentRecord u = at(sarts, i);
            String n = String.valueOf(i + 1);
            lifesaving.add(new Row("SART No. 0" + n, List.of(
                    cell("MakeSART No " + n, "Make", u == null ? null : u.make()),
                    cell("ModelSART No " + n, "Model", u == null ? null : u.model()),
                    cell("Date13_af_date." + i, "Expiration", date(ownOrBatteryExpiry(u))))));
        }
        List<EquipmentRecord> radios = units(named("WALKIE", "WALKY", "TALKIE", "TALKY", "GMDSS WT", "TWO-WAY", "TWO WAY")
                .and(not(part())));
        for (int i = 0; i < 3; i++) {
            EquipmentRecord u = at(radios, i);
            String n = String.valueOf(i + 1);
            lifesaving.add(new Row("GMDSS Walkie-Talkie No. 0" + n, List.of(
                    cell("MakeGMDSS Walkie Talkie No " + n, "Make", u == null ? null : u.make()),
                    cell("ModelGMDSS Walkie Talkie No " + n, "Model", u == null ? null : u.model()),
                    cell("Date13_af_date." + (i + 2), "Expiration", date(ownOrBatteryExpiry(u))))));
        }
        sections.add(new Section("Equipment", null, null, lifesaving));

        // ---- VDR / SVDR
        EquipmentRecord vdr = at(units(named("VDR").and(not(named("BATT", "BEACON", "FFC", "FLOAT", "CAPSULE",
                "HRU", "HYDRO", "PSU")))), 0);
        List<EquipmentRecord> vdrGroup = withParts(vdr);
        EquipmentRecord ffcBattery = under(vdr, named("FFC", "FLOAT", "CAPSULE").and(named("BATT")));
        EquipmentRecord ffcHru = under(vdr, named("FFC", "FLOAT", "CAPSULE").and(named("HRU", "HYDRO")));
        EquipmentRecord psu = under(vdr, named("PSU", "BACKUP", "BATT").and(not(named("FFC", "FLOAT", "CAPSULE"))));
        sections.add(new Section("VDR/SVDR",
                status("Dropdown4", vdrGroup), error("Error IF any T", vdrGroup),
                List.of(new Row("VDR", List.of(
                                cell("MakeVDR", "Make", vdr == null ? null : vdr.make()),
                                cell("ModelVDR", "Model", vdr == null ? null : vdr.model()))),
                        new Row("PSU Battery", List.of(cell("Date11_af_date.0", "Expiration", date(expiry(psu))))),
                        new Row("Acoustic Beacon", List.of(cell("Date11_af_date.1", "Expiration",
                                date(expiry(under(vdr, named("BEACON"))))))),
                        new Row("Float Free Capsule Battery", List.of(cell("Date11_af_date.2", "Expiration",
                                date(expiry(ffcBattery))))),
                        new Row("Float Free HRU", List.of(cell("Date11_af_date.3", "Expiration",
                                date(expiry(ffcHru))))))));

        // ---- Radars: the form lists S-band first
        List<EquipmentRecord> radars = new ArrayList<>(units(named("RADAR", "BAND").and(not(named("FAN",
                "MAGNETRON", "SCANNER", "DISPLAY", "PROCESSOR", "PCB", "MOTOR")))));
        radars.sort(Comparator.comparingInt(r -> bandOrder(band(r))));
        List<EquipmentRecord> radarGroup = radars.stream().flatMap(r -> withParts(r).stream()).toList();
        String[] bandKeys = {"Dropdown1.0", "Dropdown1.1.0", "Dropdown1.1.1"};
        List<Row> radarRows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            EquipmentRecord u = at(radars, i);
            String n = String.valueOf(i + 1);
            EquipmentRecord magnetron = under(u, named("MAGNETRON"));
            radarRows.add(new Row("Radar No. 0" + n, List.of(
                    new Cell(bandKeys[i], "Band", u == null ? "" : bandLabel(band(u)), u != null, BAND_OPTIONS),
                    cell("MakeRadar No " + n, "Make", u == null ? null : u.make()),
                    cell("ModelRadar No " + n, "Model", u == null ? null : u.model()),
                    cell("Magnetron running Hrs Tx HrsRadar No " + n, "Magnetron Running Hrs. (Tx. Hrs.)",
                            hours(magnetron == null ? null : magnetron.runningHours())))));
        }
        sections.add(new Section("Radar's", status("Dropdown5", radarGroup), error("Text12", radarGroup), radarRows));

        // ---- ECDIS
        List<EquipmentRecord> ecdis = units(named("ECDIS").and(not(named("FAN", "SSD", "PROCESSOR", "DISPLAY",
                "UPS", "PCB"))));
        List<EquipmentRecord> ecdisGroup = ecdis.stream().flatMap(r -> withParts(r).stream()).toList();
        List<Row> ecdisRows = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            EquipmentRecord u = at(ecdis, i);
            String n = String.valueOf(i + 1);
            EquipmentRecord ssd = under(u, named("SSD"));
            ecdisRows.add(new Row("ECDIS No. 0" + n, List.of(
                    cell("MakeECDIS No " + n, "Make", u == null ? null : u.make()),
                    cell("ModelECDIS No " + n, "Model", u == null ? null : u.model()),
                    cell("Date10_af_date.1." + i, "Last SSD Replacement Date",
                            date(ssd == null ? null : ssd.lastReplacement())))));
        }
        sections.add(new Section("ECDIS", status("Dropdown6", ecdisGroup), error("Error IF any T_3", ecdisGroup),
                ecdisRows));

        // ---- Gyro
        List<EquipmentRecord> gyros = units(named("GYRO").and(not(named("REPEATER", "FAN", "PCB", "SPHERE"))));
        List<EquipmentRecord> gyroGroup = gyros.stream().flatMap(r -> withParts(r).stream()).toList();
        List<Row> gyroRows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            EquipmentRecord u = at(gyros, i);
            String n = String.valueOf(i + 1);
            gyroRows.add(new Row("Gyro No. 0" + n, List.of(
                    cell("MakeGyro No " + n, "Make", u == null ? null : u.make()),
                    cell("ModelGyro No " + n, "Model", u == null ? null : u.model()),
                    cell("Date9_af_date." + i + ".0", "Annual", date(u == null ? null : u.lastAnnualServiceDate())),
                    cell("Date9_af_date." + i + ".1", "Sphere", date(sphere(u))))));
        }
        sections.add(new Section("Gyro", status("Dropdown7", gyroGroup), error("Error IF any T_4", gyroGroup),
                gyroRows));

        return new Draft(List.copyOf(sections));
    }

    // ------------------------------------------------------------- status rule

    /**
     * Good when nothing in the group is overdue, expired, or the subject of an
     * open service request; otherwise the reviewer sees why, and may override.
     */
    private Cell status(String key, List<EquipmentRecord> group) {
        String value = group.isEmpty() ? "" : issues(group).isEmpty() ? "Good" : "Not Good";
        return new Cell(key, "Status", value, !group.isEmpty(), STATUS_OPTIONS);
    }

    private Cell error(String key, List<EquipmentRecord> group) {
        List<String> issues = issues(group);
        String text = issues.size() <= MAX_ISSUES ? String.join("; ", issues)
                : String.join("; ", issues.subList(0, MAX_ISSUES)) + "; +" + (issues.size() - MAX_ISSUES) + " more";
        return new Cell(key, "Error (IF any)", text, false, null);
    }

    List<String> issues(List<EquipmentRecord> group) {
        Set<Long> ids = group.stream().map(EquipmentRecord::spareId).collect(Collectors.toSet());
        List<String> out = new ArrayList<>();
        for (RequestSummary r : openRequests) {
            if (ids.contains(r.spareId())) out.add(r.requestNumber() + " " + r.title());
        }
        for (DueItem d : overdue) {
            if (ids.contains(d.spareId()) && d.daysRemaining() != null) {
                String what = "RUNNING_HOURS".equals(d.basis()) ? "service" : "annual";
                out.add(d.spareName() + " " + what + " overdue " + Math.abs(d.daysRemaining()) + " d");
            }
        }
        for (EquipmentRecord e : group) {
            if (e.expirationDate() != null && e.expirationDate().isBefore(today)) {
                out.add(e.name() + " expired " + date(e.expirationDate()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------ the register

    /** Items that match, leaving out any whose own parts also match - "GMDSS walkie-talkie" over No. 1-3. */
    private List<EquipmentRecord> units(Predicate<EquipmentRecord> match) {
        List<EquipmentRecord> found = register.stream().filter(match).toList();
        return found.stream()
                .filter(u -> found.stream().noneMatch(o -> o != u && isUnder(o, u)))
                .toList();
    }

    /** The first part filed under a unit that matches. */
    private EquipmentRecord under(EquipmentRecord unit, Predicate<EquipmentRecord> match) {
        if (unit == null) return null;
        return register.stream().filter(e -> isUnder(e, unit)).filter(match).findFirst().orElse(null);
    }

    private List<EquipmentRecord> withParts(EquipmentRecord unit) {
        if (unit == null) return List.of();
        List<EquipmentRecord> out = new ArrayList<>();
        out.add(unit);
        register.stream().filter(e -> isUnder(e, unit)).forEach(out::add);
        return out;
    }

    /**
     * Filed under, by VMP number: 11.1.1 under 11.1 on a standard-fit vessel,
     * 11.1 under 11 on one imported from the client's sheet.
     */
    private static boolean isUnder(EquipmentRecord child, EquipmentRecord parent) {
        return child.path() != null && parent.path() != null && child.path().startsWith(parent.path() + ".");
    }

    private static Predicate<EquipmentRecord> named(String... words) {
        return e -> {
            String n = e.name() == null ? "" : e.name().toUpperCase(Locale.ROOT);
            for (String w : words) if (n.contains(w)) return true;
            return false;
        };
    }

    /** Batteries, releases and fans are parts of a unit, never the unit. */
    private static Predicate<EquipmentRecord> part() {
        return named("BATT", "HRU", "HYDRO", "RELEASE", "FAN", "BEACON");
    }

    private static Predicate<EquipmentRecord> not(Predicate<EquipmentRecord> p) {
        return p.negate();
    }

    private static EquipmentRecord at(List<EquipmentRecord> list, int i) {
        return i < list.size() ? list.get(i) : null;
    }

    private static LocalDate expiry(EquipmentRecord e) {
        return e == null ? null : e.expirationDate();
    }

    /** A SART or handheld's expiry is its battery's; recorded on the unit, or on a battery under it. */
    private LocalDate ownOrBatteryExpiry(EquipmentRecord u) {
        if (u == null) return null;
        if (u.expirationDate() != null) return u.expirationDate();
        return expiry(under(u, named("BATT")));
    }

    /** The sphere is replaced on the gyro itself, or recorded against a sphere item under it. */
    private LocalDate sphere(EquipmentRecord gyro) {
        if (gyro == null) return null;
        if (gyro.lastSphereReplacement() != null) return gyro.lastSphereReplacement();
        EquipmentRecord part = under(gyro, named("SPHERE"));
        if (part == null) return null;
        return part.lastSphereReplacement() != null ? part.lastSphereReplacement() : part.lastReplacement();
    }

    enum Band { S, X, UNKNOWN }

    static Band band(EquipmentRecord r) {
        String n = r.name() == null ? "" : r.name().toUpperCase(Locale.ROOT).replace("-", " ");
        if (n.contains("S BAND") || n.matches(".*\\bS\\s*BAND.*")) return Band.S;
        if (n.contains("X BAND") || n.matches(".*\\bX\\s*BAND.*")) return Band.X;
        return Band.UNKNOWN;
    }

    private static int bandOrder(Band b) {
        return switch (b) {
            case S -> 0;
            case X -> 1;
            case UNKNOWN -> 2;
        };
    }

    private static String bandLabel(Band b) {
        return switch (b) {
            case S -> "S-Band";
            case X -> "X-Band";
            case UNKNOWN -> "N/A";
        };
    }

    // ----------------------------------------------------------------- values

    private Cell cell(String key, String label, String value) {
        return new Cell(key, label, value == null ? "" : value.trim(), true, null);
    }

    private String date(LocalDate d) {
        return d == null ? null : dates.format(d);
    }

    private static String hours(BigDecimal h) {
        return h == null ? null : h.stripTrailingZeros().toPlainString();
    }
}
