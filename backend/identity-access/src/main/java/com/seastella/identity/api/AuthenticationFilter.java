package com.seastella.identity.api;

import jakarta.servlet.Filter;

/**
 * The filter that turns a bearer token into an authenticated request.
 *
 * <p>Published as a type the application can place in its security chain
 * without naming identity's implementation. How a token is read, and what it is
 * checked against, stays inside the module that owns sessions.
 */
public interface AuthenticationFilter extends Filter {
}
