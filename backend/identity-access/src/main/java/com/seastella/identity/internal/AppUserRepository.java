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

    long countByRole(Role role);

    @Query("select u.role, count(u) from AppUser u where u.status = 'ACTIVE' group by u.role")
    List<Object[]> countActiveByRole();
}
