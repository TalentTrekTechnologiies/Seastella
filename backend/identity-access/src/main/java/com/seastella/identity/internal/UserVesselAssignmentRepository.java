package com.seastella.identity.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface UserVesselAssignmentRepository extends JpaRepository<UserVesselAssignment, Long> {

    @Query("select a.vesselId from UserVesselAssignment a where a.userId = :userId")
    Set<Long> findVesselIdsByUserId(@Param("userId") Long userId);

    List<UserVesselAssignment> findByVesselId(Long vesselId);

    List<UserVesselAssignment> findByUserId(Long userId);

    List<UserVesselAssignment> findByUserIdIn(java.util.Collection<Long> userIds);

    void deleteByUserIdAndVesselId(Long userId, Long vesselId);

    boolean existsByUserIdAndVesselId(Long userId, Long vesselId);
}
