package com.seastella.servicerequest.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Append-only. No update or delete method is exposed. */
public interface ServiceRequestTransitionLogRepository
        extends JpaRepository<ServiceRequestTransitionLog, Long> {

    List<ServiceRequestTransitionLog> findByServiceRequestIdOrderByOccurredAtAsc(Long serviceRequestId);

    @Query("""
            select t from ServiceRequestTransitionLog t
            where t.vesselId in :ids
            order by t.occurredAt desc
            """)
    List<ServiceRequestTransitionLog> findRecentForVessels(@Param("ids") java.util.Set<Long> vesselIds,
                                                           org.springframework.data.domain.Pageable pageable);
}
