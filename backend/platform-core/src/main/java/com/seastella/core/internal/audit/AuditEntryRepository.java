package com.seastella.core.internal.audit;

import com.seastella.core.api.audit.AuditEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * Read and append only. There is deliberately no update or delete method, and
 * {@code JpaRepository#save} is never called with a detached managed entry.
 */
public interface AuditEntryRepository extends JpaRepository<AuditEntry, Long> {

    @Query("""
            select a from AuditEntry a
            where (:entityType is null or a.entityType = :entityType)
              and (:entityId is null or a.entityId = :entityId)
              and (:organizationId is null or a.organizationId = :organizationId)
              and (:from is null or a.occurredAt >= :from)
            order by a.occurredAt desc
            """)
    Page<AuditEntry> search(@Param("entityType") String entityType,
                           @Param("entityId") Long entityId,
                           @Param("organizationId") Long organizationId,
                           @Param("from") Instant from,
                           Pageable pageable);
}
