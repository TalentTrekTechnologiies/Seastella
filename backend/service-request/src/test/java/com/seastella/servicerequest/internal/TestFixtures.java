package com.seastella.servicerequest.internal;

import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeKind;
import com.seastella.servicerequest.api.Priority;
import com.seastella.servicerequest.api.ServiceRequestStatus;

import java.lang.reflect.Field;
import java.util.Set;

/** Shared builders. Ids are set reflectively since entities are never persisted here. */
final class TestFixtures {

    static final Long ORG_A = 1L;
    static final Long ORG_B = 2L;
    static final Long VESSEL_A1 = 10L;
    static final Long VESSEL_A2 = 11L;
    static final Long VESSEL_B1 = 20L;
    static final Long REQUEST_ID = 500L;

    static final Long CAPTAIN_ID = 100L;
    static final Long SHIP_MANAGER_ID = 101L;
    static final Long COORDINATOR_ID = 102L;
    static final Long ENGINEER_ID = 103L;
    static final Long OTHER_ENGINEER_ID = 104L;

    private TestFixtures() {}

    static ServiceRequest request(ServiceRequestStatus status) {
        return request(status, ORG_A, VESSEL_A1);
    }

    static ServiceRequest request(ServiceRequestStatus status, Long orgId, Long vesselId) {
        ServiceRequest r = new ServiceRequest(
                "SR-ACME-202609-0001", orgId, vesselId, 900L, CAPTAIN_ID,
                "ECDIS No.1 display fan failure",
                "Display fan intermittently stops; unit overheats after 4 hours.",
                Priority.HIGH);
        r.seedStatus(status);
        setId(r, REQUEST_ID);
        return r;
    }

    static ServiceRequest assignedRequest(ServiceRequestStatus status, Long engineerUserId) {
        ServiceRequest r = request(status);
        r.seedAssignment(engineerUserId, COORDINATOR_ID, java.time.Instant.now());
        return r;
    }

    /** Platform-wide scope. */
    static AccessScope platformScope() {
        return AccessScope.ofPlatform(1L, Role.PLATFORM_ADMIN);
    }

    /**
     * Scope covering vessels A1 and A2.
     *
     * <p>Expressed as ORGANIZATION_SET for the Coordinator, which is what the
     * role now resolves to (OI-16), and as ORGANIZATION for client-tenant roles.
     */
    static AccessScope orgScope(Role role, Long userId) {
        Set<Long> vessels = Set.of(VESSEL_A1, VESSEL_A2);
        if (role == Role.SERVICE_COORDINATOR) {
            return AccessScope.ofOrganizations(userId, role, Set.of(ORG_A), vessels);
        }
        return AccessScope.ofOrganization(userId, role, ORG_A, vessels);
    }

    /** Vessel-set scope covering exactly the given vessels. */
    static AccessScope vesselScope(Role role, Long userId, Long... vesselIds) {
        return AccessScope.ofVessels(userId, role, ORG_A, Set.of(vesselIds));
    }

    /** A different organization entirely. */
    static AccessScope otherOrgScope(Role role, Long userId) {
        if (role == Role.SERVICE_COORDINATOR) {
            return AccessScope.ofOrganizations(userId, role, Set.of(ORG_B), Set.of(VESSEL_B1));
        }
        return AccessScope.ofOrganization(userId, role, ORG_B, Set.of(VESSEL_B1));
    }

    /** Engineer scope: a job set, never a vessel set. */
    static AccessScope jobScope(Long engineerUserId, Set<Long> jobIds, Set<Long> vesselIds) {
        return AccessScope.ofJobs(engineerUserId, Role.SERVICE_ENGINEER, jobIds, vesselIds);
    }

    static void setId(Object entity, Long id) {
        try {
            Class<?> c = entity.getClass();
            Field f = null;
            while (c != null && f == null) {
                try {
                    f = c.getDeclaredField("id");
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) throw new IllegalStateException("no id field");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
