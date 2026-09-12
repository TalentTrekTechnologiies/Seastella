package com.seastella.core.api.error;

import org.springframework.http.HttpStatus;

/**
 * The resource does not exist <em>or</em> is outside the caller's scope.
 *
 * <p>Those two cases are deliberately indistinguishable. Returning 403 for an
 * out-of-scope resource confirms it exists, which turns any id path parameter
 * into an enumeration oracle - a Ship Manager could walk vessel ids and learn
 * the size and shape of another organization's fleet without ever reading a
 * record. docs/04-rbac-and-scope.md SEC-08; tests S-01, S-02.
 *
 * <p>Use {@link #ofResource} rather than a hand-written message so the log line
 * stays greppable while the response body stays generic.
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static NotFoundException ofResource(String resourceType, Object id) {
        return new NotFoundException(resourceType + " " + id + " not found or out of scope");
    }
}
