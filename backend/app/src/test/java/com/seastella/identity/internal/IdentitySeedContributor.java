package com.seastella.identity.internal;

import com.seastella.core.api.seed.SeedContext;
import com.seastella.core.api.seed.SeedContributor;
import com.seastella.identity.api.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds the six pilot roles as real users, wired through the SoW section 4.1
 * delegation chain: the Platform Admin owns the organization and its Technical
 * Head, the Technical Head owns Ship Managers and vessel allocation, and each
 * Ship Manager assigns the Captain to their own vessels.
 *
 * <p>{@code assigned_by_user_id} on every assignment records that chain, so the
 * seeded data exercises the same delegation the running system enforces rather
 * than short-circuiting it.
 *
 * <p><b>Demo credentials only.</b> Every account shares one obvious password
 * and is marked SEED; the seeder never runs under the prod profile.
 */
@Component
public class IdentitySeedContributor implements SeedContributor {

    private final AppUserRepository users;
    private final UserVesselAssignmentRepository assignments;
    private final UserOrganizationAssignmentRepository orgAssignments;
    private final PasswordEncoder passwordEncoder;

    /**
     * Shared by every seeded account. Deliberately obvious for a laptop; a
     * hosted demo sets its own through {@code DEMO_PASSWORD}, since that site is
     * reachable by anyone with the address.
     */
    private final String demoPassword;

    IdentitySeedContributor(AppUserRepository users,
                            UserVesselAssignmentRepository assignments,
                            UserOrganizationAssignmentRepository orgAssignments,
                            PasswordEncoder passwordEncoder,
                            @Value("${seastella.seed.demo-password:SeaStella#Demo2026}") String demoPassword) {
        this.users = users;
        this.assignments = assignments;
        this.orgAssignments = orgAssignments;
        this.passwordEncoder = passwordEncoder;
        if (demoPassword == null || demoPassword.length() < 12) {
            throw new IllegalStateException("seastella.seed.demo-password must be at least 12 characters");
        }
        this.demoPassword = demoPassword;
    }

    @Override public int order() { return 20; }

    @Override public String name() { return "identity (users, vessel assignments)"; }

    @Override
    public void contribute(SeedContext ctx) {
        Long acme = ctx.id("org.acme");
        Long nordic = ctx.id("org.nordic");

        // 1. Platform Admin - belongs to no organization (database CHECK).
        Long admin = user(ctx, "user.admin", "admin@seastella.example",
                "Priya Raghunathan", Role.PLATFORM_ADMIN, null);

        // 2. Platform Admin creates each organization's Technical Head.
        Long acmeTech = user(ctx, "user.tech.acme", "tech.head@acme-shipmanagement.example",
                "Arun Vaswani", Role.TECHNICAL_HEAD, acme);
        Long nordicTech = user(ctx, "user.tech.nordic", "tech.head@nordic-tanker.example",
                "Ingrid Solberg", Role.TECHNICAL_HEAD, nordic);

        // 3. Technical Head creates Ship Managers and allocates vessels.
        Long sm1 = user(ctx, "user.sm.one", "d.fernandes@acme-shipmanagement.example",
                "Dinesh Fernandes", Role.SHIP_MANAGER, acme);
        Long sm2 = user(ctx, "user.sm.two", "k.oyelaran@acme-shipmanagement.example",
                "Kemi Oyelaran", Role.SHIP_MANAGER, acme);
        Long nordicSm = user(ctx, "user.sm.nordic", "m.haugen@nordic-tanker.example",
                "Mats Haugen", Role.SHIP_MANAGER, nordic);

        assign(sm1, ctx.id("vessel.kestrel"), acmeTech);
        assign(sm1, ctx.id("vessel.brahmaputra"), acmeTech);
        assign(sm2, ctx.id("vessel.coral"), acmeTech);
        assign(sm2, ctx.id("vessel.sable"), acmeTech);
        assign(nordicSm, ctx.id("vessel.bergen"), nordicTech);
        assign(nordicSm, ctx.id("vessel.fjord"), nordicTech);

        // 4. Each Ship Manager assigns the Captain to their own vessels.
        //    A Captain is assigned to exactly one vessel (SoW s5).
        Long capKestrel = user(ctx, "user.captain.kestrel", "master.kestrel@acme-shipmanagement.example",
                "Rohan Mehta", Role.CAPTAIN, acme);
        Long capBrahma = user(ctx, "user.captain.brahmaputra", "master.brahmaputra@acme-shipmanagement.example",
                "Anatoly Vasiliev", Role.CAPTAIN, acme);
        Long capCoral = user(ctx, "user.captain.coral", "master.coral@acme-shipmanagement.example",
                "Wei Lin Tan", Role.CAPTAIN, acme);
        Long capSable = user(ctx, "user.captain.sable", "master.sable@acme-shipmanagement.example",
                "Emeka Chukwu", Role.CAPTAIN, acme);
        Long capBergen = user(ctx, "user.captain.bergen", "master.bergen@nordic-tanker.example",
                "Lars Engebretsen", Role.CAPTAIN, nordic);

        assign(capKestrel, ctx.id("vessel.kestrel"), sm1);
        assign(capBrahma, ctx.id("vessel.brahmaputra"), sm1);
        assign(capCoral, ctx.id("vessel.coral"), sm2);
        assign(capSable, ctx.id("vessel.sable"), sm2);
        assign(capBergen, ctx.id("vessel.bergen"), nordicSm);

        // 5. Seastella's own service operations (OI-16).
        //
        //    Coordinators are platform-side staff, not client-tenant members:
        //    they carry no organization_id and are scoped by explicit
        //    assignment to the client organizations they service.
        //
        //    Sofia covers BOTH clients - the case the SoW describes, and the
        //    one the previous single-organization model could not express.
        //    Jonas covers Nordic only, so the boundary has something to hold
        //    against: a test asserting Sofia sees two organizations proves
        //    little unless someone is provably excluded from one.
        Long sofia = user(ctx, "user.coordinator", "coordinator@seastella.example",
                "Sofia Marchetti", Role.SERVICE_COORDINATOR, null);
        assignOrganization(sofia, acme, admin);
        assignOrganization(sofia, nordic, admin);

        Long jonas = user(ctx, "user.coordinator.nordic", "coordinator.nordic@seastella.example",
                "Jonas Bakken", Role.SERVICE_COORDINATOR, null);
        assignOrganization(jonas, nordic, admin);

        //    Engineers are external service providers. They receive no
        //    organization assignment at all: the job is their only boundary,
        //    which is a tighter guarantee than an organization would give.
        user(ctx, "user.engineer.one", "t.okafor@marine-electronics.example",
                "Tobenna Okafor", Role.SERVICE_ENGINEER, null);
        user(ctx, "user.engineer.two", "s.nakamura@marine-electronics.example",
                "Sho Nakamura", Role.SERVICE_ENGINEER, null);
    }

    private Long user(SeedContext ctx, String handle, String email, String fullName,
                  Role role, Long organizationId) {

    Long id = users.findByEmailIgnoreCase(email)
            .map(existing -> {
                if ("SEED".equals(existing.getSeedMarker())
                        && !passwordEncoder.matches(demoPassword, existing.getPasswordHash())) {
                    existing.changePassword(passwordEncoder.encode(demoPassword));
                    users.save(existing);
                }

                return existing.getId();
            })
            .orElseGet(() -> {
                AppUser u = new AppUser(
                        email,
                        passwordEncoder.encode(demoPassword),
                        fullName,
                        role,
                        organizationId
                );
                u.markSeed();
                return users.save(u).getId();
            });

    ctx.put(handle, id);
    return id;
}

    private void assignOrganization(Long userId, Long organizationId, Long assignedBy) {
        if (!orgAssignments.existsByUserIdAndOrganizationId(userId, organizationId)) {
            orgAssignments.save(new UserOrganizationAssignment(userId, organizationId, assignedBy));
        }
    }

    private void assign(Long userId, Long vesselId, Long assignedBy) {
        if (!assignments.existsByUserIdAndVesselId(userId, vesselId)) {
            assignments.save(new UserVesselAssignment(userId, vesselId, assignedBy));
        }
    }

    /** Exposed for the seed summary log. */
    public static List<String> demoAccounts() {
        return List.of(
                "admin@seastella.example (PLATFORM_ADMIN)",
                "tech.head@acme-shipmanagement.example (TECHNICAL_HEAD)",
                "d.fernandes@acme-shipmanagement.example (SHIP_MANAGER, 2 vessels)",
                "master.kestrel@acme-shipmanagement.example (CAPTAIN)",
                "coordinator@seastella.example (SERVICE_COORDINATOR)",
                "t.okafor@marine-electronics.example (SERVICE_ENGINEER)");
    }
}
