package com.seastella.identity.api;

import java.time.Instant;

/**
 * Sends the emails that let a person into their account: the invitation to
 * set a first password, and a password-reset link.
 *
 * <p>A port declared here and implemented by the notification module, which
 * owns email delivery - so identity never depends on it. The link is passed
 * straight through to the message and never stored.
 */
public interface AccountEmails {

    Delivery sendInvitation(Recipient to, String invitedBy, String roleLabel, String link, Instant expiresAt);

    Delivery sendPasswordReset(Recipient to, String link, Instant expiresAt);

    enum Delivery {
        SENT,
        /** No mail server is configured in this environment; nothing was attempted. */
        NOT_CONFIGURED,
        FAILED
    }

    record Recipient(Long userId, String email, String fullName) {}
}
