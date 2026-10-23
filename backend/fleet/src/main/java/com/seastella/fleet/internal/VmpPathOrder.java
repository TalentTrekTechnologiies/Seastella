package com.seastella.fleet.internal;

import java.util.Comparator;

/**
 * The order the VMP template lists equipment in: 1, 2, ... 9, 10, 10.1, 10.2,
 * 11 - each dot-separated part compared as a number.
 *
 * <p>The database can only sort the path as text, which puts 10 and 11 between
 * 1 and 2. A part that is not a number (a hand-entered "A") sorts after the
 * numbered ones, as text.
 */
final class VmpPathOrder {

    static final Comparator<String> PATHS = VmpPathOrder::compare;

    static final Comparator<Spare> SPARES = Comparator.comparing(Spare::getPath, PATHS);

    private VmpPathOrder() {}

    static int compare(String a, String b) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : 1) : -1;
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = comparePart(x[i].trim(), y[i].trim());
            if (c != 0) return c;
        }
        // A parent comes before everything filed under it.
        return Integer.compare(x.length, y.length);
    }

    private static int comparePart(String a, String b) {
        boolean na = isNumber(a);
        boolean nb = isNumber(b);
        if (na && nb) {
            // Compare by length first so a part too long for a long still orders correctly.
            String ta = a.replaceFirst("^0+(?=.)", "");
            String tb = b.replaceFirst("^0+(?=.)", "");
            int c = Integer.compare(ta.length(), tb.length());
            return c != 0 ? c : ta.compareTo(tb);
        }
        if (na != nb) return na ? -1 : 1;
        return a.compareToIgnoreCase(b);
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
