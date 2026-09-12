package com.seastella.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the bearer token and populates the SecurityContext.
 *
 * <p>Rejection is silent: an unusable token simply leaves the context
 * unauthenticated and the entry point returns 401. Distinguishing "expired"
 * from "bad signature" in the response tells an attacker which half of a forged
 * token was wrong.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final AppUserRepository users;

    JwtAuthenticationFilter(JwtService jwtService, AppUserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            jwtService.parse(header.substring(PREFIX.length())).ifPresent(parsed ->
                    // Re-check the user on every request: a token issued before a
                    // suspension must stop working immediately (SEC-04, test S-45).
                    users.findById(parsed.userId())
                            .filter(AppUser::isActive)
                            .filter(u -> !u.isLocked())
                            .ifPresent(user -> {
                                SeaStellaPrincipal principal = new SeaStellaPrincipal(
                                        user.getId(), user.getEmail(), user.getRole(),
                                        user.getOrganizationId(), user.getFullName());

                                var auth = new UsernamePasswordAuthenticationToken(
                                        principal, null, principal.getAuthorities());
                                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                                SecurityContextHolder.getContext().setAuthentication(auth);
                            }));
        }
        chain.doFilter(request, response);
    }
}
