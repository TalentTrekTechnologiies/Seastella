package com.seastella.identity.internal;

import com.seastella.identity.api.AuthDtos;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account links that arrive by email, used before the person can sign in:
 * accepting an invitation and resetting a password. Public by necessity; the
 * link itself is the credential, single-use and short-lived.
 */
@RestController
@RequestMapping("/api/v1/account")
class AccountController {

    private final AccountService accounts;
    private final SessionCookies cookies;

    AccountController(AccountService accounts, SessionCookies cookies) {
        this.accounts = accounts;
        this.cookies = cookies;
    }

    @GetMapping("/invitations/{token}")
    ResponseEntity<AccountService.Pending> invitation(@PathVariable String token) {
        return ResponseEntity.ok(accounts.invitation(token));
    }

    /** Sets the first password and signs the person in. */
    @PostMapping("/invitations/{token}")
    ResponseEntity<AuthDtos.LoginResponse> acceptInvitation(@PathVariable String token, @RequestBody PasswordBody body,
                                                            HttpServletRequest http) {
        AuthService.SignedIn signedIn = accounts.acceptInvitation(token, body == null ? null : body.password(),
                AuthController.clientIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.issue(signedIn.refresh())).body(signedIn.response());
    }

    /**
     * "Forgot password". Always 202 and always immediate: the work, including
     * whether an email is sent at all, happens off the request thread, so the
     * reply reveals nothing about which addresses have accounts.
     */
    @PostMapping("/password-resets")
    ResponseEntity<Void> requestReset(@RequestBody EmailBody body) {
        accounts.requestPasswordReset(body == null ? null : body.email());   // @Async
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @GetMapping("/password-resets/{token}")
    ResponseEntity<AccountService.Pending> passwordReset(@PathVariable String token) {
        return ResponseEntity.ok(accounts.passwordReset(token));
    }

    /** Sets a new password, ends every other session, and signs the person in. */
    @PostMapping("/password-resets/{token}")
    ResponseEntity<AuthDtos.LoginResponse> completeReset(@PathVariable String token, @RequestBody PasswordBody body,
                                                         HttpServletRequest http) {
        AuthService.SignedIn signedIn = accounts.completePasswordReset(token, body == null ? null : body.password(),
                AuthController.clientIp(http), http.getHeader("User-Agent"));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.issue(signedIn.refresh())).body(signedIn.response());
    }

    record PasswordBody(String password) {}

    record EmailBody(String email) {}
}
