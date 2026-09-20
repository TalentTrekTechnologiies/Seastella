package com.seastella.identity.api;

import java.util.Optional;

/**
 * Who is signed in on this request, for code that needs an identity but not a
 * scope — the rate limiter counting a person's writes, for example.
 *
 * <p>Published so nothing outside identity has to know what a principal is
 * made of. Reading it costs nothing: it answers from the security context, not
 * from the database.
 */
public interface CurrentUser {

    /** Empty when the caller is anonymous. */
    Optional<Long> userId();
}
