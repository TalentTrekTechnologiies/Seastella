package com.seastella.app.config;

import com.seastella.identity.api.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request rate limiting (SEC-23).
 *
 * <p>Password guessing and link fishing are scripted, not typed, so sign-in and
 * the account-link endpoints are capped per client address; everything that
 * changes data is capped per signed-in user, which bounds the damage a stolen
 * token can do before anyone notices. Reads are never limited - dashboards and
 * the chat poll, and slowing an honest Captain helps nobody.
 *
 * <p>Counts are per-minute windows held in this instance's memory. That matches
 * the pilot's single backend instance (docs/09-deployment.md); more than one
 * instance needs a shared store, and the limit becomes per instance until then.
 * The lockout after repeated failed sign-ins (SEC-03) is the account-level
 * protection; this is the address-level one, and they are independent.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Above this many tracked keys, stale minutes are swept before adding more. */
    private static final int SWEEP_ABOVE = 2_000;

    /** Worth attacking with a script: credentials in, or a password set from a link. */
    private static final String[] SENSITIVE = {
            "/api/v1/auth/login", "/api/v1/auth/password", "/api/v1/account/"
    };

    private final RateLimitProperties limits;
    private final CurrentUser currentUser;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * Built by {@link SecurityConfig} inside the security chain rather than as a
     * bean, so it runs after authentication (and is registered once, not twice).
     */
    RateLimitFilter(RateLimitProperties limits, CurrentUser currentUser) {
        this.currentUser = currentUser;
        this.limits = limits;
    }

    /**
     * A sliding window over two minutes: the minute in progress, plus what is
     * left of the one before it, weighted by how much of it is still in the last
     * sixty seconds. A plain per-minute counter would let a burst straddling the
     * boundary through at twice the limit - exactly what a script would find.
     */
    private static final class Window {
        private long minute;
        private int previous;
        private int current;

        Window(long minute) {
            this.minute = minute;
        }

        void rollTo(long now) {
            if (minute == now) return;
            previous = minute == now - 1 ? current : 0;
            current = 0;
            minute = now;
        }

        double estimate(double weightOfPrevious) {
            return previous * weightOfPrevious + current;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!limits.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        boolean sensitive = isSensitive(path);
        boolean write = isWrite(request.getMethod());
        if (!sensitive && !write) {
            chain.doFilter(request, response);
            return;
        }

        int allowed = sensitive ? limits.getSensitivePerMinute() : limits.getWritesPerMinute();
        String key = (sensitive ? "s|" : "w|") + (sensitive ? clientIp(request) : actor(request));
        long seconds = Instant.now().getEpochSecond();
        long minute = seconds / 60;
        if (windows.size() > SWEEP_ABOVE) {
            windows.values().removeIf(w -> w.minute < minute - 1);
        }

        double weightOfPrevious = 1.0 - (seconds % 60) / 60.0;
        boolean[] refused = new boolean[1];
        windows.compute(key, (k, existing) -> {
            Window window = existing == null ? new Window(minute) : existing;
            window.rollTo(minute);
            // A refused request is not counted, so a caller that keeps hammering
            // does not extend their own ban indefinitely.
            if (window.estimate(weightOfPrevious) >= allowed) {
                refused[0] = true;
            } else {
                window.current++;
            }
            return window;
        });
        if (refused[0]) {
            refuse(request, response, path, sensitive, minute);
            return;
        }
        chain.doFilter(request, response);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, String path, boolean sensitive,
                        long minute) throws IOException {
        int retryAfter = (int) (60 - (Instant.now().getEpochSecond() - minute * 60));
        log.warn("rate-limited path={} ip={} scope={}", path, clientIp(request), sensitive ? "sensitive" : "writes");

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(retryAfter, 1)));
        response.getWriter().write("""
                {"type":"about:blank","title":"Too many requests","status":429,\
                "detail":"Too many requests. Wait a moment and try again.","code":"RATE_LIMITED"}""");
    }

    /** Signed-in user where there is one; otherwise the address, so anonymous writes still count. */
    private String actor(HttpServletRequest request) {
        return currentUser.userId().map(id -> "u" + id).orElseGet(() -> clientIp(request));
    }

    private static boolean isSensitive(String path) {
        for (String prefix : SENSITIVE) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean isWrite(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
    }

    /** Behind the host's proxy the first X-Forwarded-For entry is the client. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
