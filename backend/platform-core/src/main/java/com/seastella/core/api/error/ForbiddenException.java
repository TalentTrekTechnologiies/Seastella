package com.seastella.core.api.error;

import org.springframework.http.HttpStatus;

/**
 * The caller is authenticated and the resource is within their data scope, but
 * their role does not permit this operation.
 *
 * <p>Note the narrowness: this is for capability failures, not scope failures.
 * If the caller cannot see the resource at all, throw
 * {@link NotFoundException} instead - see the note there on why.
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static ForbiddenException ofAction(String action) {
        return new ForbiddenException("Not permitted: " + action);
    }
}
