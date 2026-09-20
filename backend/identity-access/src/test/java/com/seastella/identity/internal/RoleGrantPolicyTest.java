package com.seastella.identity.internal;

import com.seastella.core.api.error.ForbiddenException;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.VesselDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The SoW s4.1 provisioning chain and the privilege-escalation cases of
 * docs/04 s6 (S-10 to S-16). Each denial is asserted, not just the happy path.
 */
class RoleGrantPolicyTest {

    private static final long ACME = 1L;
    private static final long NORDIC = 2L;
    private static final long KESTREL = 10L;      // ACME, allocated to the Ship Manager below
    private static final long CORAL = 11L;        // ACME, not allocated to them
    private static final long BERGEN = 20L;       // NORDIC

    private final AccessScope admin = AccessScope.ofPlatform(100L, Role.PLATFORM_ADMIN);
    private final AccessScope acmeTechHead = AccessScope.ofOrganization(101L, Role.TECHNICAL_HEAD, ACME, Set.of(KESTREL, CORAL));
    private final AccessScope acmeShipManager = AccessScope.ofVessels(102L, Role.SHIP_MANAGER, ACME, Set.of(KESTREL));
    private final AccessScope acmeCaptain = AccessScope.ofVessels(103L, Role.CAPTAIN, ACME, Set.of(KESTREL));

    private RoleGrantPolicy policy;

    @BeforeEach
    void setUp() {
        VesselDirectory vessels = mock(VesselDirectory.class);
        when(vessels.organizationExists(ACME)).thenReturn(true);
        when(vessels.organizationExists(NORDIC)).thenReturn(true);
        when(vessels.organizationIdForVessel(KESTREL)).thenReturn(ACME);
        when(vessels.organizationIdForVessel(CORAL)).thenReturn(ACME);
        when(vessels.organizationIdForVessel(BERGEN)).thenReturn(NORDIC);
        policy = new RoleGrantPolicy(vessels);
    }

    @Nested
    @DisplayName("creating accounts")
    class Creating {

        @Test
        void platformAdminCreatesTechnicalHeadForAnExistingOrganization() {
            assertThat(policy.assertMayCreate(admin, Role.TECHNICAL_HEAD, ACME)).isEqualTo(ACME);
        }

        @Test
        void platformAdminNeedsAnOrganizationForTenantRoles() {
            assertThatThrownBy(() -> policy.assertMayCreate(admin, Role.TECHNICAL_HEAD, null))
                    .isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> policy.assertMayCreate(admin, Role.TECHNICAL_HEAD, 999L))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void seastellaStaffBelongToNoOrganization() {
            assertThat(policy.assertMayCreate(admin, Role.SERVICE_COORDINATOR, ACME)).isNull();
            assertThat(policy.assertMayCreate(admin, Role.SERVICE_ENGINEER, null)).isNull();
        }

        @Test
        void nobodyCreatesAPlatformAdminThroughTheApi() {
            assertThatThrownBy(() -> policy.assertMayCreate(admin, Role.PLATFORM_ADMIN, null))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("S-10 Technical Head cannot create a Technical Head, Coordinator or Captain")
        void technicalHeadCreatesOnlyShipManagers() {
            for (Role role : List.of(Role.TECHNICAL_HEAD, Role.SERVICE_COORDINATOR, Role.SERVICE_ENGINEER, Role.CAPTAIN)) {
                assertThatThrownBy(() -> policy.assertMayCreate(acmeTechHead, role, null))
                        .as(role.name()).isInstanceOf(ForbiddenException.class);
            }
            assertThat(policy.assertMayCreate(acmeTechHead, Role.SHIP_MANAGER, null)).isEqualTo(ACME);
        }

        @Test
        @DisplayName("S-11 Technical Head cannot create a user in another organization")
        void technicalHeadStaysInOwnOrganization() {
            assertThatThrownBy(() -> policy.assertMayCreate(acmeTechHead, Role.SHIP_MANAGER, NORDIC))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("S-12 Ship Manager creates Captains only, in own organization")
        void shipManagerCreatesOnlyCaptains() {
            assertThatThrownBy(() -> policy.assertMayCreate(acmeShipManager, Role.SHIP_MANAGER, null))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> policy.assertMayCreate(acmeShipManager, Role.CAPTAIN, NORDIC))
                    .isInstanceOf(ForbiddenException.class);
            assertThat(policy.assertMayCreate(acmeShipManager, Role.CAPTAIN, null)).isEqualTo(ACME);
        }

        @Test
        void captainCreatesNothing() {
            assertThatThrownBy(() -> policy.assertMayCreate(acmeCaptain, Role.CAPTAIN, null))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("S-16 a Phase-2 role cannot be provisioned")
        void phaseTwoRoleRefused() {
            assertThatThrownBy(() -> policy.assertMayCreate(admin, Role.CHIEF_ENGINEER, ACME))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    @DisplayName("allocating vessels to a Ship Manager")
    class Allocating {

        private final AppUser acmeManager = new AppUser("sm@acme.example", "x", "SM", Role.SHIP_MANAGER, ACME);

        @Test
        void technicalHeadAllocatesOwnVessels() {
            assertThatCode(() -> policy.assertMayAllocate(acmeTechHead, acmeManager, Set.of(KESTREL, CORAL)))
                    .doesNotThrowAnyException();
        }

        @Test
        void anotherOrganizationsVesselLooksAbsent() {
            assertThatThrownBy(() -> policy.assertMayAllocate(acmeTechHead, acmeManager, Set.of(BERGEN)))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void anotherOrganizationsShipManagerLooksAbsent() {
            AccessScope nordicTechHead = AccessScope.ofOrganization(200L, Role.TECHNICAL_HEAD, NORDIC, Set.of(BERGEN));
            assertThatThrownBy(() -> policy.assertMayAllocate(nordicTechHead, acmeManager, Set.of(BERGEN)))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void shipManagerCannotAllocate() {
            assertThatThrownBy(() -> policy.assertMayAllocate(acmeShipManager, acmeManager, Set.of(KESTREL)))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Nested
    @DisplayName("assigning a Captain")
    class AssigningCaptain {

        private final AppUser acmeCaptainUser = new AppUser("cap@acme.example", "x", "Capt", Role.CAPTAIN, ACME);

        @Test
        void shipManagerAssignsToOwnAllocatedVessel() {
            assertThatCode(() -> policy.assertMayAssignCaptain(acmeShipManager, acmeCaptainUser, KESTREL))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("S-13 Ship Manager cannot assign a Captain to an unallocated vessel")
        void unallocatedVesselRefused() {
            assertThatThrownBy(() -> policy.assertMayAssignCaptain(acmeShipManager, acmeCaptainUser, CORAL))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void otherOrganizationsVesselLooksAbsent() {
            assertThatThrownBy(() -> policy.assertMayAssignCaptain(acmeShipManager, acmeCaptainUser, BERGEN))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void onlyCaptainAccountsCanBeCaptain() {
            AppUser manager = new AppUser("sm2@acme.example", "x", "SM2", Role.SHIP_MANAGER, ACME);
            assertThatThrownBy(() -> policy.assertMayAssignCaptain(acmeShipManager, manager, KESTREL))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        void technicalHeadDoesNotAssignCaptains() {
            assertThatThrownBy(() -> policy.assertMayAssignCaptain(acmeTechHead, acmeCaptainUser, KESTREL))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Nested
    @DisplayName("changing account status")
    class Status {

        @Test
        void shipManagerSuspendsOwnCaptainOnly() {
            AppUser own = new AppUser("cap@acme.example", "x", "Capt", Role.CAPTAIN, ACME);
            assertThatCode(() -> policy.assertMayChangeStatus(acmeShipManager, own, Set.of(KESTREL)))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> policy.assertMayChangeStatus(acmeShipManager, own, Set.of(CORAL)))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void technicalHeadCannotSuspendAnotherTechnicalHead() {
            AppUser peer = new AppUser("th2@acme.example", "x", "TH2", Role.TECHNICAL_HEAD, ACME);
            assertThatThrownBy(() -> policy.assertMayChangeStatus(acmeTechHead, peer, Set.of()))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void nobodySuspendsAPlatformAdmin() {
            AppUser otherAdmin = new AppUser("admin2@seastella.example", "x", "Admin 2", Role.PLATFORM_ADMIN, null);
            assertThatThrownBy(() -> policy.assertMayChangeStatus(admin, otherAdmin, Set.of()))
                    .isInstanceOf(ForbiddenException.class);
        }
    }
}
