package com.seastella.identity.internal;

import com.seastella.core.api.error.ValidationException;

/**
 * A phone number as WhatsApp needs it: country code and digits, "+919876543210".
 *
 * <p>People type numbers every way there is - spaces, dashes, brackets, a
 * leading 00 - so those are folded. A bare ten-digit Indian mobile number
 * (starting 6 to 9) is taken as +91, because that is how most of this
 * platform's users will write their own. Anything else must carry its
 * country code: guessing one would send a vessel's alerts to a stranger.
 */
final class WhatsAppNumbers {

    static final String HINT = "Enter the number with its country code, for example +91 98765 43210.";

    private WhatsAppNumbers() {}

    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().replaceAll("[\\s\\-().]", "");
        if (s.startsWith("00")) s = "+" + s.substring(2);
        if (!s.startsWith("+") && s.matches("[6-9]\\d{9}")) s = "+91" + s;
        if (!s.matches("\\+[1-9]\\d{7,14}")) {
            throw new ValidationException(HINT);
        }
        return s;
    }
}
