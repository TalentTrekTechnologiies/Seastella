package com.seastella.identity.internal;

import com.seastella.core.api.error.ForbiddenException;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.VesselDirectory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Objects;

/**
 * <b>Who may grant what</b> - the SoW s4.1 provisioning chain, in one place
 * (docs/04 s2).
 *
 * <pre>
 * Platform Admin ──creates──▶ Organization, its Technical Head,
 *                             Seastella's Coordinators and Engineers
 * Technical Head ──creates──▶ Ship Managers, own organization only
 * Technical Head ──allocates─▶ own-organization vessels → Ship Manager
 * Ship Manager   ──assigns──▶ Captain → own allocated vessels only
 * </pre>
 *
 * <p>No role grants a role at or above its own. Every provisioning endpoint
 * calls this class rather than checking roles itself, so the chain cannot drift
 * apart controller by controller - and the privilege-escalation tests S-10 to
 * S-16 exercise exactly these methods.
 *
 * <p>Creating a user <em>somewhere</em> the actor may not is 403, because the
 * actor named the organization themselves. Acting on an existing user or vessel
 * outside the actor's scope is 404, as everywhere else: an id must not confirm
 * that a record exists.
 */
@Component
class RoleGrantPolicy {

    private final VesselDirectory vessels;

    RoleGrantPolicy(VesselDirectory vessels) {
        this.vessels = vessels;
    }

    /**
     * Checks that the actor may create an account with this role in this
     * organization, and returns the organization the account belongs to.
     */
    Long assertMayCreate(AccessScope actor, Role target, Long requestedOrganizationId) {
        if (target == null) {
            throw new ValidationException("Choose the role for the new account.");
        }
        if (!target.isEnabled()) {
            // S-16: declared for Phase 2, not provisionable in the pilot.
            throw new ValidationException(label(target) + " is not available in the pilot.");
        }
        if (target == Role.PLATFORM_ADMIN) {
            throw ForbiddenException.ofAction("create a Platform Admin account");
        }

        Role role = actor.role();
        if (role == Role.PLATFORM_ADMIN) {
            if (target.isPlatformSide()) {
                // Coordinators and Engineers belong to no client organization
                // (OI-16); the database CHECK agrees.
                return null;
            }
            if (requestedOrganizationId == null) {
                throw new ValidationException("Choose the organization this " + label(target) + " belongs to.");
            }
            if (!vessels.organizationExists(requestedOrganizationId)) {
                throw NotFoundException.ofResource("Organization", requestedOrganizationId);
            }
            return requestedOrganizationId;
        }

        if (role == Role.TECHNICAL_HEAD) {
            if (target != Role.SHIP_MANAGER) {
                throw ForbiddenException.ofAction("create a " + label(target));          // S-10
            }
            assertOwnOrganization(actor, requestedOrganizationId);                         // S-11
            return actor.organizationId();
        }

        if (role == Role.SHIP_MANAGER) {
            if (target != Role.CAPTAIN) {
                throw ForbiddenException.ofAction("create a " + label(target));          // S-12
            }
            assertOwnOrganization(actor, requestedOrganizationId);
            return actor.organizationId();
        }

        throw ForbiddenException.ofAction("create accounts");
    }

    /**
     * The Technical Head (or Platform Admin) allocating vessels to a Ship
     * Manager. Every vessel must belong to the Ship Manager's organization.
     */
    void assertMayAllocate(AccessScope actor, AppUser shipManager, Collection<Long> vesselIds) {
        if (actor.role() != Role.PLATFORM_ADMIN && actor.role() != Role.TECHNICAL_HEAD) {
            throw ForbiddenException.ofAction("allocate vessels");
        }
        assertVisible(actor, shipManager);
        if (shipManager.getRole() != Role.SHIP_MANAGER) {
            throw new ValidationException("Vessels are allocated to Ship Managers only.");
        }
        for (Long vesselId : vesselIds) {
            Long owner = vessels.organizationIdForVessel(vesselId);
            if (owner == null) {
                throw NotFoundException.ofResource("Vessel", vesselId);
            }
            if (!owner.equals(shipManager.getOrganizationId())) {
                if (actor.role() == Role.TECHNICAL_HEAD) {
                    // Outside the Technical Head's fleet: indistinguishable from absent.
                    throw NotFoundException.ofResource("Vessel", vesselId);
                }
                throw new ValidationException("A Ship Manager can only be allocated vessels of their own organization.");
            }
        }
    }

    /**
     * The Ship Manager (or Platform Admin) assigning a Captain to a vessel.
     * A Ship Manager may use only vessels already allocated to them (S-13).
     */
    void assertMayAssignCaptain(AccessScope actor, AppUser captain, Long vesselId) {
        if (actor.role() != Role.PLATFORM_ADMIN && actor.role() != Role.SHIP_MANAGER) {
            throw ForbiddenException.ofAction("assign Captains");
        }
        Long owner = vessels.organizationIdForVessel(vesselId);
        if (owner == null || (actor.role() == Role.SHIP_MANAGER && !actor.organizationIds().contains(owner))) {
            throw NotFoundException.ofResource("Vessel", vesselId);
        }
        if (actor.role() == Role.SHIP_MANAGER && !actor.vesselIds().contains(vesselId)) {
            throw ForbiddenException.ofAction("assign a Captain to a vessel not allocated to you");   // S-13
        }
        if (captain.getRole() != Role.CAPTAIN) {
            throw new ValidationException("Only a Captain account can be assigned as Captain.");
        }
        if (!Objects.equals(captain.getOrganizationId(), owner)) {
            throw new ValidationException("The Captain must belong to the vessel's organization.");
        }
    }

    /** Suspending or reactivating an account: only down the chain that created it. */
    void assertMayChangeStatus(AccessScope actor, AppUser target, Collection<Long> targetVesselIds) {
        if (Objects.equals(actor.userId(), target.getId())) {
            throw ForbiddenException.ofAction("change the status of your own account");
        }
        boolean allowed = switch (actor.role()) {
            case PLATFORM_ADMIN -> target.getRole() != Role.PLATFORM_ADMIN;
            case TECHNICAL_HEAD -> target.getRole() == Role.SHIP_MANAGER
                    && Objects.equals(target.getOrganizationId(), actor.organizationId());
            case SHIP_MANAGER -> target.getRole() == Role.CAPTAIN
                    && Objects.equals(target.getOrganizationId(), actor.organizationId())
                    && actor.vesselIds().containsAll(targetVesselIds)
                    && !targetVesselIds.isEmpty();
            default -> false;
        };
        if (!allowed) {
            assertVisible(actor, target);
            throw ForbiddenException.ofAction("change this account's status");
        }
    }

    /** 404 for a user the actor has no business knowing about. */
    void assertVisible(AccessScope actor, AppUser target) {
        boolean visible = actor.role() == Role.PLATFORM_ADMIN
                || ((actor.role() == Role.TECHNICAL_HEAD || actor.role() == Role.SHIP_MANAGER)
                && target.getOrganizationId() != null
                && target.getOrganizationId().equals(actor.organizationId()));
        if (!visible) {
            throw NotFoundException.ofResource("User", target.getId());
        }
    }

    private static void assertOwnOrganization(AccessScope actor, Long requested) {
        if (requested != null && !requested.equals(actor.organizationId())) {
            throw ForbiddenException.ofAction("create accounts in another organization");
        }
    }

    static String label(Role role) {
        return switch (role) {
            case PLATFORM_ADMIN -> "Platform Admin";
            case TECHNICAL_HEAD -> "Technical Head";
            case SHIP_MANAGER -> "Ship Manager";
            case CAPTAIN -> "Captain";
            case SERVICE_COORDINATOR -> "Service Coordinator";
            case SERVICE_ENGINEER -> "Service Engineer";
            case CHIEF_ENGINEER -> "Chief Engineer";
        };
    }
}
