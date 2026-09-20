package com.seastella.identity.internal;

import com.seastella.identity.api.AccountEmails;
import com.seastella.identity.api.Role;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Account administration down the SoW s4.1 chain (IAM-02 to IAM-08).
 *
 * <p>The role annotations are the coarse gate - who may reach the endpoint at
 * all. What each role may actually grant, and where, is decided by
 * {@link RoleGrantPolicy} inside the service, so a Technical Head reaching
 * {@code POST /users} still cannot create anything but a Ship Manager in their
 * own organization.
 *
 * <p>Nobody sets or sees another person's password. Creating an account emails
 * an invitation; "reset password" emails a link. Both are sent only after the
 * change has committed, so an email never points at an account that was rolled
 * back.
 */
@RestController
@RequestMapping("/api/v1")
class ProvisioningController {

    private final ProvisioningService provisioning;
    private final AccountEmails emails;
    private final AccountLinks urls;

    ProvisioningController(ProvisioningService provisioning, AccountEmails emails, AccountLinks urls) {
        this.provisioning = provisioning;
        this.emails = emails;
        this.urls = urls;
    }

    /**
     * What happened to the email. {@code link} is present only when the email
     * was not delivered to the mail server - no mail server configured, or it
     * refused - so the administrator can pass it on by another channel. Once an
     * email is on its way the link is never shown to anyone but its recipient.
     */
    record LinkSent(ProvisioningService.UserSummary user, AccountEmails.Delivery email,
                    Instant expiresAt, String link) {}

    @GetMapping("/users")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TECHNICAL_HEAD','SHIP_MANAGER')")
    ResponseEntity<List<ProvisioningService.UserSummary>> list() {
        return ResponseEntity.ok(provisioning.list());
    }

    @PostMapping("/users")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TECHNICAL_HEAD','SHIP_MANAGER')")
    ResponseEntity<LinkSent> create(@RequestBody CreateUserBody body) {
        if (body == null) body = new CreateUserBody(null, null, null, null, null, null, null);
        ProvisioningService.AccountLink created = provisioning.create(new ProvisioningService.CreateUser(
                body.fullName(), body.email(), body.role(), body.organizationId(),
                body.vesselId(), body.vesselIds(), body.organizationIds()));
        return ResponseEntity.status(HttpStatus.CREATED).body(sendInvitation(created));
    }

    /** A fresh invitation; the previous link stops working. */
    @PostMapping("/users/{userId}/invitation")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TECHNICAL_HEAD','SHIP_MANAGER')")
    ResponseEntity<LinkSent> resendInvitation(@PathVariable Long userId) {
        return ResponseEntity.ok(sendInvitation(provisioning.resendInvitation(userId)));
    }

    /** Emails a reset link. The current password keeps working until the link is used. */
    @PostMapping("/users/{userId}/password-reset")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TECHNICAL_HEAD','SHIP_MANAGER')")
    ResponseEntity<LinkSent> sendPasswordReset(@PathVariable Long userId) {
        ProvisioningService.AccountLink reset = provisioning.sendPasswordReset(userId);
        String link = urls.passwordReset(reset.link().rawToken());
        AccountEmails.Delivery delivery = emails.sendPasswordReset(recipient(reset), link, reset.link().expiresAt());
        return ResponseEntity.ok(result(reset, delivery, link));
    }

    /** Correct a name or a sign-in address (IAM-08). */
    @PutMapping("/users/{userId}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TECHNICAL_HEAD','SHIP_MANAGER')")
    ResponseEntity<ProvisioningService.UserSummary> updateProfile(@PathVariable Long userId,
                                                                  @RequestBody ProfileBody body) {
        return ResponseEntity.ok(provisioning.updateProfile(userId,
                body == null ? null : body.fullName(), body == null ? null : body.email()));
    }

    /** Technical Head: exactly which vessels this Ship Manager is responsible for. */
    @PutMapping("/users/{userId}/vessels")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TECHNICAL_HEAD')")
    ResponseEntity<ProvisioningService.UserSummary> allocate(@PathVariable Long userId,
                                                             @RequestBody VesselAllocationBody body) {
        return ResponseEntity.ok(provisioning.allocateVessels(userId, body == null ? Set.of() : body.vesselIds()));
    }

    /** Ship Manager: the Captain of one of their vessels. */
    @PutMapping("/vessels/{vesselId}/captain")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','SHIP_MANAGER')")
    ResponseEntity<ProvisioningService.UserSummary> assignCaptain(@PathVariable Long vesselId,
                                                                  @RequestBody CaptainBody body) {
        return ResponseEntity.ok(provisioning.assignCaptain(vesselId, body == null ? null : body.captainUserId()));
    }

    @PostMapping("/users/{userId}/status")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TECHNICAL_HEAD','SHIP_MANAGER')")
    ResponseEntity<ProvisioningService.UserSummary> status(@PathVariable Long userId, @RequestBody StatusBody body) {
        return ResponseEntity.ok(provisioning.changeStatus(userId, body == null ? null : body.status()));
    }

    private LinkSent sendInvitation(ProvisioningService.AccountLink invitation) {
        String link = urls.invitation(invitation.link().rawToken());
        AccountEmails.Delivery delivery = emails.sendInvitation(recipient(invitation), invitation.sentBy(),
                invitation.user().roleLabel(), link, invitation.link().expiresAt());
        return result(invitation, delivery, link);
    }

    private static AccountEmails.Recipient recipient(ProvisioningService.AccountLink account) {
        return new AccountEmails.Recipient(account.user().id(), account.user().email(), account.user().fullName());
    }

    private static LinkSent result(ProvisioningService.AccountLink account, AccountEmails.Delivery delivery, String link) {
        return new LinkSent(account.user(), delivery, account.link().expiresAt(),
                delivery == AccountEmails.Delivery.SENT ? null : link);
    }

    record CreateUserBody(String fullName, String email, Role role, Long organizationId,
                          Long vesselId, Set<Long> vesselIds, Set<Long> organizationIds) {}

    record VesselAllocationBody(Set<Long> vesselIds) {}

    record CaptainBody(Long captainUserId) {}

    record StatusBody(String status) {}

    record ProfileBody(String fullName, String email) {}
}
