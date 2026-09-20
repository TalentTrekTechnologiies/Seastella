package com.seastella.identity.internal;

import com.seastella.identity.api.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<AppUser> findByOrganizationIdAndRole(Long organizationId, Role role);

    List<AppUser> findByOrganizationId(Long organizationId);

    List<AppUser> findByRoleOrderByFullNameAsc(Role role);

    long countByRole(Role role);

    /** Active holders of a role who are assigned to the vessel (Ship Manager, Captain). */
    @Query("""
            select u from AppUser u
            where u.role = :role and u.status = 'ACTIVE'
              and u.id in (select a.userId from UserVesselAssignment a where a.vesselId = :vesselId)
            order by u.fullName
            """)
    List<AppUser> findActiveByRoleOnVessel(@Param("role") Role role, @Param("vesselId") Long vesselId);

    /**
     * Active holders of a role who belong to the organization (Technical Head)
     * or are assigned to serve it (Service Coordinator, OI-16).
     */
    @Query("""
            select u from AppUser u
            where u.role = :role and u.status = 'ACTIVE'
              and (u.organizationId = :orgId
                   or u.id in (select a.userId from UserOrganizationAssignment a where a.organizationId = :orgId))
            order by u.fullName
            """)
    List<AppUser> findActiveByRoleForOrganization(@Param("role") Role role, @Param("orgId") Long organizationId);

    @Query("select u.role, count(u) from AppUser u where u.status = 'ACTIVE' group by u.role")
    List<Object[]> countActiveByRole();
}
