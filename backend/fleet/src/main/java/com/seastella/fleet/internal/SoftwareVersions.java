package com.seastella.fleet.internal;

import com.seastella.fleet.api.SoftwareStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orders two marine-equipment software versions.
 *
 * <p>These strings are typed by hand off a device's About screen, and the
 * fleet carries whatever a dozen manufacturers print there: {@code 5.6},
 * {@code v5.6}, {@code 5.06}, {@code 5.6.1}, {@code 2.1.0-rc3},
 * {@code 5.6 (build 214)}. A plain string comparison calls 5.10 older than
 * 5.9, and a plain {@code Double.parseDouble} throws on most of the above.
 *
 * <p>So: pull out the run of dot-separated numbers, compare them
 * left-to-right as integers, and treat a missing component as zero, so
 * {@code 5.6} and {@code 5.6.0} are the same release. Anything after the
 * numbers - {@code -rc3}, {@code (build 214)} - is compared only when the
 * numbers are equal, and then only for equality, never for order: this class
 * has no business deciding whether {@code rc3} precedes {@code beta}. Two
 * releases that differ solely in that tail are reported
 * {@link SoftwareStatus#UNKNOWN} rather than guessed at.
 *
 * <p>{@code 5.06} against {@code 5.6} is the one genuinely ambiguous case.
 * Read as integers they are 6 and 6 - equal - which is what most marine
 * manufacturers mean by it, and what this class does.
 */
final class SoftwareVersions {

    /** The leading numeric part: digits and dots, after an optional "v". */
    private static final Pattern NUMERIC_HEAD = Pattern.compile("^[vV]?\\s*(\\d+(?:\\.\\d+)*)\\s*(.*)$");

    private SoftwareVersions() {
    }

    /**
     * Compares what a unit is running against the latest release for its
     * model.
     *
     * @param installed the version on the unit, may be {@code null} or blank
     * @param latest    the baseline for the model, may be {@code null} or blank
     */
    static SoftwareStatus compare(String installed, String latest) {
        Parsed on = Parsed.of(installed);
        Parsed want = Parsed.of(latest);
        if (on == null || want == null) return SoftwareStatus.UNKNOWN;

        int order = compareNumbers(on.numbers(), want.numbers());
        if (order < 0) return SoftwareStatus.OUTDATED;
        if (order > 0) return SoftwareStatus.AHEAD;

        // Same numbers. A differing tail - rc3 against release, say - is a
        // real difference this class cannot order, so it says so.
        return on.tail().equals(want.tail()) ? SoftwareStatus.CURRENT : SoftwareStatus.UNKNOWN;
    }

    /** Left-to-right, with a missing component read as zero. */
    private static int compareNumbers(List<Long> left, List<Long> right) {
        int width = Math.max(left.size(), right.size());
        for (int i = 0; i < width; i++) {
            long a = i < left.size() ? left.get(i) : 0L;
            long b = i < right.size() ? right.get(i) : 0L;
            if (a != b) return Long.compare(a, b);
        }
        return 0;
    }

    /** A version split into its orderable numbers and its unorderable tail. */
    private record Parsed(List<Long> numbers, String tail) {

        /** @return {@code null} when there is no version to compare at all. */
        static Parsed of(String raw) {
            if (raw == null || raw.isBlank()) return null;
            Matcher m = NUMERIC_HEAD.matcher(raw.trim());
            if (!m.matches()) return null;

            List<Long> numbers = new ArrayList<>();
            for (String part : m.group(1).split("\\.")) {
                try {
                    numbers.add(Long.parseLong(part));
                } catch (NumberFormatException overflow) {
                    // A component too long to be a version number is not one.
                    return null;
                }
            }
            String tail = m.group(2).trim().toLowerCase(Locale.ROOT);
            return new Parsed(numbers, tail);
        }
    }
}
