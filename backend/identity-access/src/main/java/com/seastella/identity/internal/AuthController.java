package com.seastella.identity.internal;

import com.seastella.identity.api.AuthDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Sign-in, session renewal, sign-out and changing one's own password.
 *
 * <p>The access token travels in the response body and lives only in the
 * browser's memory. The refresh token never reaches JavaScript: it is an
 * httpOnly, SameSite=Strict cookie limited to {@code /api/v1/auth}. Renewal and
 * sign-out additionally require the {@code X-Requested-With} header, which a
 * cross-site form cannot send - defence in depth on top of SameSite.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    static final String CSRF_HEADER = "X-Requested-With";
    static final String CSRF_VALUE = "SeaStella";

    private final AuthService authService;
    private final AccountService accounts;
    private final RefreshTokenService refreshTokens;
    private final SessionCookies cookies;

    AuthController(AuthService authService, AccountService accounts, RefreshTokenService refreshTokens,
                   SessionCookies cookies) {
        this.authService = authService;
        this.accounts = accounts;
        this.refreshTokens = refreshTokens;
        this.cookies = cookies;
    }

    @PostMapping("/login")
    ResponseEntity<AuthDtos.LoginResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                                 HttpServletRequest http) {
        AuthService.SignedIn signedIn = authService.login(request, clientIp(http), http.getHeader("User-Agent"));
        return started(signedIn);
    }

    /** Exchanges the refresh cookie for a new access token and a rotated cookie. */
    @PostMapping("/refresh")
    ResponseEntity<?> refresh(@CookieValue(name = SessionCookies.NAME, required = false) String raw,
                              @RequestHeader(name = CSRF_HEADER, required = false) String csrf,
                              HttpServletRequest http) {
        if (!CSRF_VALUE.equals(csrf)) {
            return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "This request is not allowed.", false);
        }
        Optional<RefreshTokenService.Issued> next = refreshTokens.rotate(raw, clientIp(http), http.getHeader("User-Agent"));
        if (next.isEmpty()) {
            return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Your session has ended. Sign in again.", true);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.issue(next.get()))
                .body(authService.session(next.get().user()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = SessionCookies.NAME, required = false) String raw,
                                @RequestHeader(name = CSRF_HEADER, required = false) String csrf) {
        if (!CSRF_VALUE.equals(csrf)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        refreshTokens.signOut(raw);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear()).build();
    }

    /**
     * Changes the signed-in user's own password. Every other session ends; this
     * one continues on a fresh cookie.
     */
    @PostMapping("/password")
    ResponseEntity<AuthDtos.LoginResponse> changePassword(@AuthenticationPrincipal SeaStellaPrincipal principal,
                                                          @RequestBody PasswordChange body,
                                                          HttpServletRequest http) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        return started(accounts.changePassword(principal.userId(),
                body == null ? null : body.currentPassword(), body == null ? null : body.newPassword(),
                clientIp(http), http.getHeader("User-Agent")));
    }

    /** The caller's own profile, re-read from the database rather than the token. */
    @GetMapping("/me")
    ResponseEntity<AuthDtos.UserProfile> me(@AuthenticationPrincipal SeaStellaPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(authService.currentProfile(principal.userId()));
    }

    private ResponseEntity<AuthDtos.LoginResponse> started(AuthService.SignedIn signedIn) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.issue(signedIn.refresh()))
                .body(signedIn.response());
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail, boolean clearCookie) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (clearCookie) builder.header(HttpHeaders.SET_COOKIE, cookies.clear());
        return builder.body(pd);
    }

    /** Behind the host's proxy the first X-Forwarded-For entry is the client. */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    record PasswordChange(String currentPassword, String newPassword) {}
}
