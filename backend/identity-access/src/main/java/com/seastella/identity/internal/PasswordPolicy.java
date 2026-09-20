package com.seastella.identity.internal;

import com.seastella.core.api.error.ValidationException;

import java.util.Locale;
import java.util.Set;

/**
 * What a password must be (SEC-01, B s33).
 *
 * <p>Length over composition rules, as current guidance recommends: long
 * passphrases are both stronger and easier to remember than short strings
 * forced to contain a symbol. On top of length, a password may not contain
 * the account's own email name or be one of the passwords that lead every
 * breached-password list.
 */
final class PasswordPolicy {

    static final int MIN_LENGTH = 12;
    static final int MAX_LENGTH = 128;

    private static final Set<String> COMMON = Set.of(
            "password1234", "123456789012", "qwertyuiop12", "passwordpassword", "letmein12345",
            "welcome12345", "administrator", "seastella123", "iloveyou1234", "changeme1234");

    private PasswordPolicy() {
    }

    static void check(String password, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new ValidationException("Use at least " + MIN_LENGTH + " characters. A short phrase is easiest to remember.");
        }
        if (password.length() > MAX_LENGTH) {
            throw new ValidationException("Use at most " + MAX_LENGTH + " characters.");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (password.chars().distinct().count() < 5) {
            throw new ValidationException("Choose a password with more variety.");
        }
        if (COMMON.contains(lower)) {
            throw new ValidationException("That password is too common. Choose another.");
        }
        if (email != null && email.contains("@")) {
            String name = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
            if (name.length() >= 4 && lower.contains(name)) {
                throw new ValidationException("Don't include your email name in the password.");
            }
        }
    }
}
