package com.seastella.identity.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface UserOrganizationAssignmentRepository
        extends JpaRepository<UserOrganizationAssignment, Long> {

    @Query("select a.organizationId from UserOrganizationAssignment a where a.userId = :userId")
    Set<Long> findOrganizationIdsByUserId(@Param("userId") Long userId);

    List<UserOrganizationAssignment> findByUserId(Long userId);

    boolean existsByUserIdAndOrganizationId(Long userId, Long organizationId);
}
