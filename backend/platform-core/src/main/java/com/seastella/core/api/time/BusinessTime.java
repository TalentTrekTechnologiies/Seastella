package com.seastella.core.api.time;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The time zone the business runs on: what "today" is for due dates, overdue
 * payments and certificate expiry, and the zone times are printed in on
 * reports and emails. Indian Standard Time by default; set
 * {@code SEASTELLA_TIME_ZONE} (an IANA zone id, e.g. {@code Asia/Singapore})
 * to change it.
 *
 * <p>Only the reading of time changes. Moments are still stored as instants
 * (UTC in the database), so nothing already recorded changes meaning.
 */
public final class BusinessTime {

    public static final ZoneId ZONE = ZoneId.of(configured());

    /** Short name printed after a time, e.g. "14:30 IST". */
    public static final String LABEL = "Asia/Kolkata".equals(ZONE.getId()) ? "IST"
            : ZONE.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH);

    private BusinessTime() {
    }

    /** Today's date where the business is. */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    private static String configured() {
        String zone = System.getProperty("seastella.time-zone");
        if (zone == null || zone.isBlank()) zone = System.getenv("SEASTELLA_TIME_ZONE");
        return zone == null || zone.isBlank() ? "Asia/Kolkata" : zone.trim();
    }
}
