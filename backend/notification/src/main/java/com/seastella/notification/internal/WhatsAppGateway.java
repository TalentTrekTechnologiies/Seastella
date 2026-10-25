package com.seastella.notification.internal;

import java.util.List;

/**
 * Sends one approved template message to one WhatsApp number.
 *
 * <p>WhatsApp, unlike email, never carries free text to someone who has not
 * written first: every alert is a template Meta approved in advance, with the
 * alert's own words passed in as its parameters.
 */
interface WhatsAppGateway {

    /** Whether the Meta account details are in place. Until they are, nothing is attempted. */
    boolean configured();

    /** @return Meta's id for the accepted message */
    String send(Outgoing message);

    /**
     * @param to             "+919876543210"
     * @param template       the approved template's name, "thawe_alert"
     * @param language       its language code, "en"
     * @param bodyParameters the template's {{1}}, {{2}}... in order
     * @param linkSuffix     the variable end of the template's link button, or null when it has none
     */
    record Outgoing(String to, String template, String language, List<String> bodyParameters, String linkSuffix) {}

    /** Meta refused or could not be reached. {@code permanent} when trying again will not help. */
    class WhatsAppException extends RuntimeException {
        private final boolean permanent;

        WhatsAppException(String message, boolean permanent) {
            super(message);
            this.permanent = permanent;
        }

        boolean permanent() { return permanent; }
    }
}
