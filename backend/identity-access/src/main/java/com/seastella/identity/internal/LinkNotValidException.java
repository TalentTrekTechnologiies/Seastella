package com.seastella.identity.internal;

import com.seastella.core.api.error.ApiException;
import org.springframework.http.HttpStatus;

/**
 * An invitation or reset link that cannot be used: unknown, used, revoked or
 * expired. The four are one answer on purpose - the person needs a new link
 * whichever it is, and telling them apart would help nobody but a guesser.
 */
class LinkNotValidException extends ApiException {

    LinkNotValidException() {
        super(HttpStatus.GONE, "LINK_NOT_VALID",
                "This link is no longer valid. It may have been used already or have expired. Ask for a new one.");
    }

    @Override
    public boolean isMessageSafeToExpose() {
        return true;
    }
}
