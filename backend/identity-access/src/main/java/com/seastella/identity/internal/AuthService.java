package com.seastella.identity.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditService;
import com.seastella.identity.api.AuthDtos;
import com.seastella.identity.api.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Sign-in.
 *
 * <p>Three things are deliberate here:
 *
 * <ol>
 *   <li><b>One failure message.</b> An unknown address, a wrong password and a
 *       suspended account all return the same {@code BadCredentialsException}.
 *       Distinguishing them turns the login form into an account-enumeration
 *       oracle.</li>
 *   <li><b>The password is verified even when the user does not exist</b>,
 *       against a dummy hash, so response timing does not reveal whether an
 *       address is registered.</li>
 *   <li><b>Lockout is recorded, not just applied</b> (SEC-03): a burst of
 *       failures against one account is a security event, and the audit trail
 *       is where that becomes visible.</li>
 * </ol>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * A real BCrypt hash of a value nobody holds. Compared against when the
     * account is absent, purely to keep the timing profile flat.
     */
    private static final String DUMMY_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEe.rMqqYQZKX3m3vX1qYYbXwSJqJ1PqLKu";

    private final AppUserRepository users;
    private final UserVesselAssignmentRepository assignments;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SecurityProperties properties;
    private final AuditService audit;
    private final RefreshTokenService refreshTokens;

    AuthService(AppUserRepository users, UserVesselAssignmentRepository assignments,
                PasswordEncoder passwordEncoder, JwtService jwtService,
                SecurityProperties properties, AuditService audit, RefreshTokenService refreshTokens) {
        this.users = users;
        this.assignments = assignments;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.properties = properties;
        this.audit = audit;
        this.refreshTokens = refreshTokens;
    }

    /** A successful sign-in: the response body, and the refresh token for the cookie. */
    public record SignedIn(AuthDtos.LoginResponse response, RefreshTokenService.Issued refresh) {}

    @Transactional
    public SignedIn login(AuthDtos.LoginRequest request, String ip, String userAgent) {
        Optional<AppUser> found = users.findByEmailIgnoreCase(request.email());

        if (found.isEmpty()) {
            // Still hash, so an absent account is not faster than a present one.
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            log.info("login-failed reason=unknown-account");
            throw new BadCredentialsException("Invalid email or password.");
        }

        AppUser user = found.get();

        if (user.isLocked()) {
            audit.record(AuditAction.LOGIN_FAILED, "AppUser", user.getId(), null, "locked");
            throw new LockedException("Account temporarily locked. Try again later.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            boolean nowLocked = user.recordFailedLogin(
                    properties.getLockout().getMaxFailedAttempts(),
                    properties.getLockout().getLockoutDuration());
            users.save(user);

            audit.record(nowLocked ? AuditAction.ACCOUNT_LOCKED : AuditAction.LOGIN_FAILED,
                    "AppUser", user.getId(), null, nowLocked ? "threshold reached" : "bad password");

            log.info("login-failed userId={} locked={}", user.getId(), nowLocked);
            throw new BadCredentialsException("Invalid email or password.");
        }

        if (!user.isActive()) {
            // Same message as a bad password: a suspended account must not be
            // distinguishable from a non-existent one.
            audit.record(AuditAction.LOGIN_FAILED, "AppUser", user.getId(), null, "inactive");
            throw new BadCredentialsException("Invalid email or password.");
        }

        user.recordSuccessfulLogin();
        users.save(user);
        audit.record(AuditAction.LOGIN_SUCCEEDED, "AppUser", user.getId(), null, null);

        return new SignedIn(session(user), refreshTokens.issueForNewSession(user, ip, userAgent));
    }

    /** A fresh access token and profile for a user whose refresh token was just rotated. */
    @Transactional(readOnly = true)
    public AuthDtos.LoginResponse session(AppUser user) {
        return new AuthDtos.LoginResponse(
                jwtService.issueAccessToken(user),
                "Bearer",
                jwtService.accessTokenTtlSeconds(),
                profile(user));
    }

    @Transactional(readOnly = true)
    public AuthDtos.UserProfile currentProfile(Long userId) {
        return users.findById(userId).map(this::profile).orElse(null);
    }

    private AuthDtos.UserProfile profile(AppUser user) {
        Role role = user.getRole();
        return new AuthDtos.UserProfile(
                user.getId(), user.getEmail(), user.getFullName(),
                role, label(role), user.getOrganizationId(),
                assignments.findVesselIdsByUserId(user.getId()),
                role.canSeeFinancials());
    }

    private static String label(Role role) {
        return switch (role) {
            case PLATFORM_ADMIN -> "Platform Administrator";
            case TECHNICAL_HEAD -> "Technical Head";
            case SHIP_MANAGER -> "Ship Manager";
            case CAPTAIN -> "Captain";
            case SERVICE_COORDINATOR -> "Service Coordinator";
            case SERVICE_ENGINEER -> "Service Engineer";
            case CHIEF_ENGINEER -> "Chief Engineer";
        };
    }
}
