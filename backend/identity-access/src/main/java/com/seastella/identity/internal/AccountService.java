package com.seastella.identity.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
import com.seastella.identity.api.AccountEmails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.Optional;

/**
 * A person's own way into their account: accepting an invitation, resetting a
 * forgotten password, changing a known one.
 *
 * <p>Every path ends by setting a password only the person knows, and each
 * that changes a password also ends every other session, so an old device or
 * a stolen cookie stops working.
 */
@Service
class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AppUserRepository users;
    private final UserTokenService links;
    private final RefreshTokenService refreshTokens;
    private final AuthService auth;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final AccountLinks urls;
    private final AccountEmails emails;
    private final TransactionTemplate tx;

    AccountService(AppUserRepository users, UserTokenService links, RefreshTokenService refreshTokens,
                   AuthService auth, PasswordEncoder passwords, AuditService audit,
                   AccountLinks urls, AccountEmails emails, PlatformTransactionManager transactions) {
        this.tx = new TransactionTemplate(transactions);
        this.users = users;
        this.links = links;
        this.refreshTokens = refreshTokens;
        this.auth = auth;
        this.passwords = passwords;
        this.audit = audit;
        this.urls = urls;
        this.emails = emails;
    }

    record Pending(String fullName, String email, String roleLabel) {}

    // ------------------------------------------------------------ invitations

    @Transactional(readOnly = true)
    Pending invitation(String rawToken) {
        UserToken token = links.usable(rawToken, UserToken.Purpose.INVITATION).orElseThrow(LinkNotValidException::new);
        AppUser user = users.findById(token.getUserId()).filter(u -> "INVITED".equals(u.getStatus()))
                .orElseThrow(LinkNotValidException::new);
        return new Pending(user.getFullName(), user.getEmail(), RoleGrantPolicy.label(user.getRole()));
    }

    @Transactional
    AuthService.SignedIn acceptInvitation(String rawToken, String password, String ip, String userAgent) {
        UserToken token = links.usable(rawToken, UserToken.Purpose.INVITATION).orElseThrow(LinkNotValidException::new);
        AppUser user = users.findById(token.getUserId()).filter(u -> "INVITED".equals(u.getStatus()))
                .orElseThrow(LinkNotValidException::new);
        PasswordPolicy.check(password, user.getEmail());

        user.changePassword(passwords.encode(password));
        user.setStatus("ACTIVE");
        user.recordSuccessfulLogin();
        users.save(user);
        links.consume(token);
        record(AuditAction.INVITATION_ACCEPTED, user);

        return new AuthService.SignedIn(auth.session(user), refreshTokens.issueForNewSession(user, ip, userAgent));
    }

    // -------------------------------------------------------- password resets

    @Transactional(readOnly = true)
    Pending passwordReset(String rawToken) {
        UserToken token = links.usable(rawToken, UserToken.Purpose.PASSWORD_RESET).orElseThrow(LinkNotValidException::new);
        AppUser user = users.findById(token.getUserId()).filter(AppUser::isActive).orElseThrow(LinkNotValidException::new);
        return new Pending(user.getFullName(), user.getEmail(), RoleGrantPolicy.label(user.getRole()));
    }

    @Transactional
    AuthService.SignedIn completePasswordReset(String rawToken, String password, String ip, String userAgent) {
        UserToken token = links.usable(rawToken, UserToken.Purpose.PASSWORD_RESET).orElseThrow(LinkNotValidException::new);
        AppUser user = users.findById(token.getUserId()).filter(AppUser::isActive).orElseThrow(LinkNotValidException::new);
        PasswordPolicy.check(password, user.getEmail());

        user.changePassword(passwords.encode(password));
        user.recordSuccessfulLogin();
        users.save(user);
        links.consume(token);
        links.revokeOpen(user.getId(), UserToken.Purpose.PASSWORD_RESET);
        refreshTokens.revokeAllFor(user.getId(), "PASSWORD_RESET");
        record(AuditAction.PASSWORD_CHANGED, user);

        return new AuthService.SignedIn(auth.session(user), refreshTokens.issueForNewSession(user, ip, userAgent));
    }

    /**
     * "Forgot password": emails a reset link if the address belongs to an
     * active account, and does nothing otherwise. The caller answers the same
     * either way and runs this off the request thread, so neither the reply nor
     * its timing reveals whether an account exists.
     */
    @Async
    public void requestPasswordReset(String email) {
        try {
            Optional<Issue> issued = tx.execute(status -> issueSelfServiceReset(email));
            issued.ifPresent(i -> emails.sendPasswordReset(
                    new AccountEmails.Recipient(i.user().getId(), i.user().getEmail(), i.user().getFullName()),
                    urls.passwordReset(i.link().rawToken()), i.link().expiresAt()));
        } catch (RuntimeException e) {
            log.error("Password reset request could not be completed", e);
        }
    }

    private record Issue(AppUser user, UserTokenService.Issued link) {}

    private Optional<Issue> issueSelfServiceReset(String email) {
        if (email == null || email.isBlank() || email.length() > 254) return Optional.empty();
        Optional<AppUser> account = users.findByEmailIgnoreCase(email.trim()).filter(AppUser::isActive);
        if (account.isPresent() && !links.selfServiceResetAllowed(account.get().getId())) {
            log.warn("password-reset-throttled userId={}", account.get().getId());
            return Optional.empty();
        }
        return account.map(user -> {
            UserTokenService.Issued link = links.issue(user.getId(), UserToken.Purpose.PASSWORD_RESET, null);
            audit.record(AuditEntry.builder()
                    .actor(user.getId(), user.getRole().name())
                    .action(AuditAction.PASSWORD_RESET)
                    .entity("AppUser", user.getId())
                    .scope(user.getOrganizationId(), null)
                    .build());
            return new Issue(user, link);
        });
    }

    // --------------------------------------------------------- own password

    @Transactional
    AuthService.SignedIn changePassword(Long userId, String currentPassword, String newPassword,
                                        String ip, String userAgent) {
        AppUser user = users.findById(userId).filter(AppUser::isActive)
                .orElseThrow(() -> NotFoundException.ofResource("User", userId));
        if (currentPassword == null || !passwords.matches(currentPassword, user.getPasswordHash())) {
            throw new ValidationException("Your current password is not correct.");
        }
        if (passwords.matches(newPassword == null ? "" : newPassword, user.getPasswordHash())) {
            throw new ValidationException("Choose a password different from your current one.");
        }
        PasswordPolicy.check(newPassword, user.getEmail());

        user.changePassword(passwords.encode(newPassword));
        users.save(user);
        refreshTokens.revokeAllFor(user.getId(), "PASSWORD_RESET");
        record(AuditAction.PASSWORD_CHANGED, user);
        return new AuthService.SignedIn(auth.session(user), refreshTokens.issueForNewSession(user, ip, userAgent));
    }

    // ------------------------------------------------------------ own email

    /**
     * Changes the signed-in user's own sign-in address.
     *
     * <p>The address is how a person signs in and where their reset links go,
     * so this asks for the current password: an unlocked screen left on a desk
     * must not be enough to take an account over by pointing it at somebody
     * else's inbox. Every other session ends, open reset and invitation links
     * die with the old address, and this device carries on with a fresh
     * session - the same shape as changing one's own password.
     *
     * <p>It is the only way a Platform Admin can correct their own address. The
     * administrator's correction of someone else's address follows the same
     * chain as suspension, which refuses to act on oneself, and nobody stands
     * above a Platform Admin in that chain.
     */
    @Transactional
    AuthService.SignedIn changeEmail(Long userId, String currentPassword, String newEmail,
                                     String ip, String userAgent) {
        AppUser user = users.findById(userId).filter(AppUser::isActive)
                .orElseThrow(() -> NotFoundException.ofResource("User", userId));
        if (currentPassword == null || !passwords.matches(currentPassword, user.getPasswordHash())) {
            throw new ValidationException("Your current password is not correct.");
        }
        String address = newEmail == null ? "" : newEmail.trim().toLowerCase(Locale.ROOT);
        if (address.isEmpty() || address.length() > 254 || !ProvisioningService.EMAIL.matcher(address).matches()) {
            throw new ValidationException("Enter a valid email address.");
        }
        if (address.equals(user.getEmail())) {
            throw new ValidationException("That is already your email address.");
        }
        if (users.existsByEmailIgnoreCase(address)) {
            throw new WorkflowException("An account already exists for " + address + ".");
        }

        String before = user.getEmail();
        user.changeEmail(address);
        users.save(user);
        refreshTokens.revokeAllFor(user.getId(), "EMAIL_CHANGED");
        links.revokeOpen(user.getId(), UserToken.Purpose.PASSWORD_RESET);
        links.revokeOpen(user.getId(), UserToken.Purpose.INVITATION);
        audit.record(AuditEntry.builder()
                .actor(user.getId(), user.getRole().name())
                .action(AuditAction.USER_UPDATED)
                .entity("AppUser", user.getId())
                .scope(user.getOrganizationId(), null)
                .before(AuditJson.of("email", before))
                .after(AuditJson.of("email", address, "signedOutEverywhere", true))
                .build());
        return new AuthService.SignedIn(auth.session(user), refreshTokens.issueForNewSession(user, ip, userAgent));
    }

    private void record(String action, AppUser user) {
        audit.record(AuditEntry.builder()
                .actor(user.getId(), user.getRole().name())
                .action(action)
                .entity("AppUser", user.getId())
                .scope(user.getOrganizationId(), null)
                .build());
    }

}
