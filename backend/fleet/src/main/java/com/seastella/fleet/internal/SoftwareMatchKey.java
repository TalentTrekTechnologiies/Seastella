package com.seastella.fleet.internal;

import java.util.Locale;

/**
 * Folds a make and model into the key a software baseline is matched on.
 *
 * <p>The two sides of this match are typed by different people years apart:
 * the client's master sheet says {@code Furuno / FA-170}, and a vessel's
 * equipment record says {@code FURUNO / FA170} or {@code Furuno / FA 170}.
 * Matching those literally finds nothing, and a miss here does not look like a
 * bug - it looks like "no baseline recorded", which is indistinguishable from
 * the model genuinely not being on the sheet. That is the failure this class
 * exists to prevent, so it is deliberately generous: case, spaces, hyphens,
 * underscores, dots and slashes all fold away.
 *
 * <p>What it will <em>not</em> do is fold digits or letters together. {@code
 * FA-170} and {@code FA-171} are different devices with different release
 * histories, and a match between them would report a vessel as current against
 * another model's version - worse than reporting nothing at all.
 */
final class SoftwareMatchKey {

    private SoftwareMatchKey() {
    }

    /**
     * @return the key for this make and model, or {@code null} when either is
     *         missing - equipment without both cannot be matched to a
     *         baseline, and a key of {@code "|"} would match every other such
     *         row to each other.
     */
    static String of(String make, String model) {
        String left = fold(make);
        String right = fold(model);
        if (left.isEmpty() || right.isEmpty()) return null;
        return left + "|" + right;
    }

    private static String fold(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
