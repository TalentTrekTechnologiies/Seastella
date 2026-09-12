package com.seastella.core.api.error;

import org.springframework.http.HttpStatus;

/**
 * Base class for errors that map to a deliberate HTTP response.
 *
 * <p>Every subclass carries a stable {@code code} that clients may switch on;
 * the message is for operators and is never returned to the caller verbatim
 * unless {@link #isMessageSafeToExpose()} says so (SEC-21).
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }

    /**
     * Whether {@link #getMessage()} may be shown to the caller. Defaults to
     * false: messages are for logs. Subclasses that carry deliberately
     * user-facing text (validation, workflow conflicts) override this.
     */
    public boolean isMessageSafeToExpose() { return false; }
}
