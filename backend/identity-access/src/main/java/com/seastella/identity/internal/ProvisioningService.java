package com.seastella.identity.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.ForbiddenException;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.identity.api.VesselDirectory;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Account provisioning down the SoW s4.1 chain.
 *
 * <p>Every method asks {@link RoleGrantPolicy} first and writes only after it
 * agrees, and every change is audited with who made it (AUD-12) - the
 * {@code assigned_by_user_id} columns record the same chain in the data itself.
 *
 * <p>Two cardinalities are enforced here: a Captain is assigned to exactly one
 * vessel and a vessel has one Captain (SoW s5), and a vessel has one
 * responsible Ship Manager (OI-20). Assigning over an existing holder moves the
 * vessel, and the move is audited on both sides.
 */
@Service
class ProvisioningService {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");

    private final AppUserRepository users;
    private final UserVesselAssignmentRepository vesselAssignments;
    private final UserOrganizationAssignmentRepository organizationAssignments;
    private final RoleGrantPolicy policy;
    private final ScopeResolver scopes;
    private final VesselDirectory vessels;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final RefreshTokenService refreshTokens;
    private final UserTokenService links;

    ProvisioningService(AppUserRepository users, UserVesselAssignmentRepository vesselAssignments,
                        UserOrganizationAssignmentRepository organizationAssignments, RoleGrantPolicy policy,
                        ScopeResolver scopes, VesselDirectory vessels, PasswordEncoder passwords, AuditService audit,
                        RefreshTokenService refreshTokens, UserTokenService links) {
        this.refreshTokens = refreshTokens;
        this.links = links;
        this.users = users;
        this.vesselAssignments = vesselAssignments;
        this.organizationAssignments = organizationAssignments;
        this.policy = policy;
        this.scopes = scopes;
        this.vessels = vessels;
        this.passwords = passwords;
        this.audit = audit;
    }

    record CreateUser(String fullName, String email, Role role, Long organizationId,
                      Long vesselId, Set<Long> vesselIds, Set<Long> organizationIds) {}

    record UserSummary(Long id, String fullName, String email, Role role, String roleLabel,
                       Long organizationId, String status, List<Long> vesselIds,
                       List<Long> serviceOrganizationIds, Instant lastLoginAt, Instant createdAt) {}

    /**
     * An account and a fresh link for it - an invitation or a password reset -
     * for the caller to email. The raw link exists only in this object.
     */
    record AccountLink(UserSummary user, String sentBy, UserTokenService.Issued link) {}

    // ------------------------------------------------------------------ read

    /** The accounts this actor administers: all, own organization, or own Captains. */
    @Transactional(readOnly = true)
    List<UserSummary> list() {
        AccessScope actor = scopes.currentScope();
        List<AppUser> found = switch (actor.role()) {
            case PLATFORM_ADMIN -> users.findAll(Sort.by("role", "fullName"));
            case TECHNICAL_HEAD -> users.findByOrganizationId(actor.organizationId());
            case SHIP_MANAGER -> captainsAvailableTo(actor);
            default -> throw ForbiddenException.ofAction("list accounts");
        };
        return summaries(found);
    }

    // ----------------------------------------------------------------- write

    /**
     * Creates the account as INVITED with no usable password, and issues the
     * invitation link. The person chooses their own password from the email;
     * nobody else ever knows it.
     */
    @Transactional
    AccountLink create(CreateUser command) {
        AccessScope actor = scopes.currentScope();
        Long organizationId = policy.assertMayCreate(actor, command.role(), command.organizationId());

        String fullName = required(command.fullName(), "Enter the person's full name.", 160);
        String email = command.email() == null ? "" : command.email().trim().toLowerCase();
        if (email.isEmpty() || email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw new ValidationException("Enter a valid email address.");
        }
        if (users.existsByEmailIgnoreCase(email)) {
            throw new WorkflowException("An account already exists for " + email + ".");
        }

        AppUser invited = new AppUser(email, passwords.encode(unusablePassword()), fullName, command.role(), organizationId);
        invited.setStatus("INVITED");
        AppUser user = users.save(invited);

        audit.record(entry(actor, AuditAction.USER_CREATED, "AppUser", user.getId(), organizationId)
                .after(AuditJson.of("email", email, "fullName", fullName, "role", command.role().name(),
                        "organizationId", organizationId, "status", "INVITED"))
                .build());
        UserTokenService.Issued invitation = links.issue(user.getId(), UserToken.Purpose.INVITATION, actor.userId());
        audit.record(entry(actor, AuditAction.INVITATION_SENT, "AppUser", user.getId(), organizationId)
                .after(AuditJson.of("expiresAt", invitation.expiresAt())).build());

        if (command.role() == Role.SERVICE_COORDINATOR && command.organizationIds() != null) {
            for (Long orgId : command.organizationIds()) {
                if (!vessels.organizationExists(orgId)) throw NotFoundException.ofResource("Organization", orgId);
                organizationAssignments.save(new UserOrganizationAssignment(user.getId(), orgId, actor.userId()));
                audit.record(entry(actor, AuditAction.ORGANIZATION_ASSIGNED, "AppUser", user.getId(), orgId)
                        .after(AuditJson.of("organizationId", orgId)).build());
            }
        }
        if (command.role() == Role.SHIP_MANAGER && command.vesselIds() != null && !command.vesselIds().isEmpty()) {
            applyAllocation(actor, user, command.vesselIds());
        }
        if (command.role() == Role.CAPTAIN && command.vesselId() != null) {
            applyCaptain(actor, user, command.vesselId());
        }
        return new AccountLink(summary(user), actorName(actor), invitation);
    }

    /** Sets exactly which vessels a Ship Manager is responsible for. */
    @Transactional
    UserSummary allocateVessels(Long shipManagerId, Set<Long> vesselIds) {
        AccessScope actor = scopes.currentScope();
        AppUser shipManager = load(shipManagerId);
        applyAllocation(actor, shipManager, vesselIds == null ? Set.of() : vesselIds);
        return summary(shipManager);
    }

    @Transactional
    UserSummary assignCaptain(Long vesselId, Long captainId) {
        AccessScope actor = scopes.currentScope();
        if (captainId == null) throw new ValidationException("Choose the Captain to assign.");
        AppUser captain = load(captainId);
        applyCaptain(actor, captain, vesselId);
        return summary(captain);
    }

    /**
     * Corrects a person's name or sign-in address (IAM-08), down the same chain
     * as suspension.
     *
     * <p>Changing the address changes how they sign in, so every session ends
     * and any link already emailed to the old address stops working. Someone
     * who has not accepted their invitation needs a new one, sent to the new
     * address; the caller is told so.
     */
    @Transactional
    UserSummary updateProfile(Long userId, String fullName, String email) {
        AccessScope actor = scopes.currentScope();
        AppUser target = load(userId);
        policy.assertMayChangeStatus(actor, target, vesselAssignments.findVesselIdsByUserId(userId));

        String name = required(fullName == null ? target.getFullName() : fullName,
                "Enter the person's full name.", 160);
        String address = email == null ? target.getEmail() : email.trim().toLowerCase();
        if (address.isEmpty() || address.length() > 254 || !EMAIL.matcher(address).matches()) {
            throw new ValidationException("Enter a valid email address.");
        }
        boolean addressChanged = !address.equals(target.getEmail());
        if (addressChanged && users.existsByEmailIgnoreCase(address)) {
            throw new WorkflowException("An account already exists for " + address + ".");
        }
        if (name.equals(target.getFullName()) && !addressChanged) {
            return summary(target);
        }

        String before = AuditJson.of("fullName", target.getFullName(), "email", target.getEmail());
        target.rename(name);
        if (addressChanged) {
            target.changeEmail(address);
        }
        users.save(target);

        if (addressChanged) {
            refreshTokens.revokeAllFor(target.getId(), "EMAIL_CHANGED");
            links.revokeOpen(target.getId(), UserToken.Purpose.PASSWORD_RESET);
            links.revokeOpen(target.getId(), UserToken.Purpose.INVITATION);
        }
        audit.record(entry(actor, AuditAction.USER_UPDATED, "AppUser", target.getId(), target.getOrganizationId())
                .before(before)
                .after(AuditJson.of("fullName", name, "email", address, "signedOutEverywhere", addressChanged))
                .build());
        return summary(target);
    }

    @Transactional
    UserSummary changeStatus(Long userId, String status) {
        if (!"ACTIVE".equals(status) && !"SUSPENDED".equals(status)) {
            throw new ValidationException("Status must be ACTIVE or SUSPENDED.");
        }
        AccessScope actor = scopes.currentScope();
        AppUser target = load(userId);
        policy.assertMayChangeStatus(actor, target, vesselAssignments.findVesselIdsByUserId(userId));

        String before = target.getStatus();
        if ("INVITED".equals(before) && "ACTIVE".equals(status)) {
            throw new WorkflowException(target.getFullName()
                    + " activates the account by accepting the invitation. Resend it if it has expired.");
        }
        if ("SUSPENDED".equals(before) && "ACTIVE".equals(status) && links.neverAccepted(target.getId())) {
            // Reactivating someone who never set a password returns them to the
            // invitation stage; an active account nobody can sign into helps no one.
            status = "INVITED";
        }
        if (!before.equals(status)) {
            target.setStatus(status);
            users.save(target);
            if ("SUSPENDED".equals(status)) {
                // Signed out everywhere at once, and no pending link still works.
                refreshTokens.revokeAllFor(target.getId(), "ACCOUNT_SUSPENDED");
                links.revokeOpen(target.getId(), UserToken.Purpose.INVITATION);
                links.revokeOpen(target.getId(), UserToken.Purpose.PASSWORD_RESET);
            }
            audit.record(entry(actor, AuditAction.USER_STATUS_CHANGED, "AppUser", target.getId(), target.getOrganizationId())
                    .before(AuditJson.of("status", before)).after(AuditJson.of("status", status)).build());
        }
        return summary(target);
    }

    /** A new invitation for someone who has not accepted theirs; the earlier link stops working. */
    @Transactional
    AccountLink resendInvitation(Long userId) {
        AccessScope actor = scopes.currentScope();
        AppUser target = load(userId);
        policy.assertMayChangeStatus(actor, target, vesselAssignments.findVesselIdsByUserId(userId));
        if (!"INVITED".equals(target.getStatus())) {
            throw new WorkflowException(target.getFullName() + " has already set up the account. "
                    + ("ACTIVE".equals(target.getStatus()) ? "Send a password reset instead." : "Reactivate it first."));
        }
        UserTokenService.Issued invitation = links.issue(target.getId(), UserToken.Purpose.INVITATION, actor.userId());
        audit.record(entry(actor, AuditAction.INVITATION_SENT, "AppUser", target.getId(), target.getOrganizationId())
                .after(AuditJson.of("expiresAt", invitation.expiresAt(), "resent", true)).build());
        return new AccountLink(summary(target), actorName(actor), invitation);
    }

    /**
     * A password-reset link, down the same chain as suspension. The password is
     * unchanged until the person follows the link and chooses a new one.
     */
    @Transactional
    AccountLink sendPasswordReset(Long userId) {
        AccessScope actor = scopes.currentScope();
        AppUser target = load(userId);
        policy.assertMayChangeStatus(actor, target, vesselAssignments.findVesselIdsByUserId(userId));
        if ("INVITED".equals(target.getStatus())) {
            throw new WorkflowException(target.getFullName() + " has not accepted the invitation yet. Resend it instead.");
        }
        if (!target.isActive()) {
            throw new WorkflowException("Reactivate " + target.getFullName() + "'s account before resetting the password.");
        }
        UserTokenService.Issued reset = links.issue(target.getId(), UserToken.Purpose.PASSWORD_RESET, actor.userId());
        audit.record(entry(actor, AuditAction.PASSWORD_RESET, "AppUser", target.getId(), target.getOrganizationId())
                .after(AuditJson.of("expiresAt", reset.expiresAt())).build());
        return new AccountLink(summary(target), actorName(actor), reset);
    }

    private String actorName(AccessScope actor) {
        return users.findById(actor.userId()).map(AppUser::getFullName).orElse("SeaStella");
    }

    /** A password nobody knows, so an invited account cannot be signed into before it is accepted. */
    private static String unusablePassword() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    // ------------------------------------------------------------- internals

    private void applyAllocation(AccessScope actor, AppUser shipManager, Set<Long> desired) {
        policy.assertMayAllocate(actor, shipManager, desired);
        Set<Long> current = vesselAssignments.findVesselIdsByUserId(shipManager.getId());

        for (Long vesselId : current) {
            if (!desired.contains(vesselId)) {
                unassign(actor, shipManager.getId(), vesselId, shipManager.getOrganizationId());
            }
        }
        for (Long vesselId : desired) {
            if (current.contains(vesselId)) continue;
            // One responsible Ship Manager per vessel (OI-20): the vessel moves.
            for (UserVesselAssignment other : holders(vesselId, Role.SHIP_MANAGER)) {
                unassign(actor, other.getUserId(), vesselId, shipManager.getOrganizationId());
            }
            assign(actor, shipManager.getId(), vesselId, shipManager.getOrganizationId());
        }
    }

    private void applyCaptain(AccessScope actor, AppUser captain, Long vesselId) {
        policy.assertVisible(actor, captain);
        policy.assertMayAssignCaptain(actor, captain, vesselId);

        List<UserVesselAssignment> captainsOther = vesselAssignments.findByUserId(captain.getId()).stream()
                .filter(a -> !a.getVesselId().equals(vesselId))
                .toList();
        if (actor.role() == Role.SHIP_MANAGER
                && captainsOther.stream().anyMatch(a -> !actor.vesselIds().contains(a.getVesselId()))) {
            throw ForbiddenException.ofAction("move a Captain who is serving on another manager's vessel");
        }

        // A Captain serves on one vessel, and a vessel has one Captain (SoW s5).
        for (UserVesselAssignment a : captainsOther) {
            unassign(actor, captain.getId(), a.getVesselId(), captain.getOrganizationId());
        }
        for (UserVesselAssignment other : holders(vesselId, Role.CAPTAIN)) {
            if (!other.getUserId().equals(captain.getId())) {
                unassign(actor, other.getUserId(), vesselId, captain.getOrganizationId());
            }
        }
        if (!vesselAssignments.existsByUserIdAndVesselId(captain.getId(), vesselId)) {
            assign(actor, captain.getId(), vesselId, captain.getOrganizationId());
        }
    }

    private List<UserVesselAssignment> holders(Long vesselId, Role role) {
        List<UserVesselAssignment> onVessel = vesselAssignments.findByVesselId(vesselId);
        Map<Long, AppUser> byId = users.findAllById(onVessel.stream().map(UserVesselAssignment::getUserId).toList())
                .stream().collect(Collectors.toMap(AppUser::getId, u -> u));
        return onVessel.stream()
                .filter(a -> byId.containsKey(a.getUserId()) && byId.get(a.getUserId()).getRole() == role)
                .toList();
    }

    private void assign(AccessScope actor, Long userId, Long vesselId, Long organizationId) {
        vesselAssignments.save(new UserVesselAssignment(userId, vesselId, actor.userId()));
        audit.record(entry(actor, AuditAction.VESSEL_ASSIGNED, "AppUser", userId, organizationId)
                .scope(organizationId, vesselId)
                .after(AuditJson.of("vesselId", vesselId)).build());
    }

    private void unassign(AccessScope actor, Long userId, Long vesselId, Long organizationId) {
        vesselAssignments.deleteByUserIdAndVesselId(userId, vesselId);
        vesselAssignments.flush();
        audit.record(entry(actor, AuditAction.VESSEL_UNASSIGNED, "AppUser", userId, organizationId)
                .scope(organizationId, vesselId)
                .before(AuditJson.of("vesselId", vesselId)).build());
    }

    /** A Ship Manager chooses among Captains who are free or already on their own vessels. */
    private List<AppUser> captainsAvailableTo(AccessScope actor) {
        List<AppUser> captains = users.findByOrganizationIdAndRole(actor.organizationId(), Role.CAPTAIN);
        Map<Long, Set<Long>> onVessels = vesselsByUser(captains.stream().map(AppUser::getId).toList());
        return captains.stream()
                .filter(c -> {
                    Set<Long> v = onVessels.getOrDefault(c.getId(), Set.of());
                    return v.isEmpty() || actor.vesselIds().containsAll(v);
                })
                .toList();
    }

    private AppUser load(Long userId) {
        AccessScope actor = scopes.currentScope();
        AppUser user = users.findById(userId).orElseThrow(() -> NotFoundException.ofResource("User", userId));
        policy.assertVisible(actor, user);
        return user;
    }

    private Map<Long, Set<Long>> vesselsByUser(Collection<Long> userIds) {
        if (userIds.isEmpty()) return Map.of();
        return vesselAssignments.findByUserIdIn(userIds).stream().collect(Collectors.groupingBy(
                UserVesselAssignment::getUserId,
                Collectors.mapping(UserVesselAssignment::getVesselId, Collectors.toCollection(LinkedHashSet::new))));
    }

    private List<UserSummary> summaries(List<AppUser> found) {
        Map<Long, Set<Long>> onVessels = vesselsByUser(found.stream().map(AppUser::getId).toList());
        return found.stream().map(u -> toSummary(u, onVessels.getOrDefault(u.getId(), Set.of()))).toList();
    }

    private UserSummary summary(AppUser user) {
        return toSummary(user, vesselAssignments.findVesselIdsByUserId(user.getId()));
    }

    private UserSummary toSummary(AppUser u, Set<Long> vesselIds) {
        List<Long> serviceOrgs = u.getRole() == Role.SERVICE_COORDINATOR
                ? List.copyOf(organizationAssignments.findOrganizationIdsByUserId(u.getId()))
                : List.of();
        return new UserSummary(u.getId(), u.getFullName(), u.getEmail(), u.getRole(),
                RoleGrantPolicy.label(u.getRole()), u.getOrganizationId(), u.getStatus(),
                List.copyOf(vesselIds), serviceOrgs, u.getLastLoginAt(), u.getCreatedAt());
    }

    private static AuditEntry.Builder entry(AccessScope actor, String action, String entityType, Long id, Long orgId) {
        return AuditEntry.builder()
                .actor(actor.userId(), actor.role() == null ? null : actor.role().name())
                .action(action)
                .entity(entityType, id)
                .scope(orgId, null);
    }

    private static String required(String value, String message, int max) {
        String t = value == null ? "" : value.trim();
        if (t.isEmpty()) throw new ValidationException(message);
        if (t.length() > max) throw new ValidationException("Keep this under " + max + " characters.");
        return t;
    }
}
