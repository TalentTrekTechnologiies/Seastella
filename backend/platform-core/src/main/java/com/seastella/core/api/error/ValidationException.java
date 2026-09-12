package com.seastella.core.api.error;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Server-side validation failure (SEC-14). Field errors are exposed. */
public class ValidationException extends ApiException {

    private final transient Map<String, String> fieldErrors;

    public ValidationException(String message) {
        this(message, Map.of());
    }

    public ValidationException(String message, Map<String, String> fieldErrors) {
        super(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
        this.fieldErrors = fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors);
    }

    public Map<String, String> getFieldErrors() { return fieldErrors; }

    @Override
    public boolean isMessageSafeToExpose() { return true; }
}
