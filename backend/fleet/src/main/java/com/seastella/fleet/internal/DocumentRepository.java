package com.seastella.fleet.internal;

import com.seastella.fleet.api.DocumentDirectory.DocumentType;
import com.seastella.fleet.api.DocumentDirectory.OwnerType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByOwnerTypeAndOwnerIdOrderByIdDesc(OwnerType ownerType, Long ownerId);

    List<Document> findByVesselIdOrderByIdDesc(Long vesselId);

    Optional<Document> findByIdAndVesselId(Long id, Long vesselId);

    /** Current certificates - not replaced, not removed - expiring by a date. */
    @Query("""
            select d from Document d
            where d.documentType = :type and d.supersededById is null and d.removedAt is null
              and d.expiryDate is not null and d.expiryDate <= :date
            order by d.expiryDate asc, d.id asc
            """)
    List<Document> certificatesExpiringBy(@Param("type") DocumentType type, @Param("date") LocalDate date);

    @Query("""
            select d from Document d
            where d.documentType = :type and d.supersededById is null and d.removedAt is null
              and d.vesselId in :vesselIds and d.expiryDate is not null
            order by d.expiryDate asc, d.id asc
            """)
    List<Document> certificatesForVessels(@Param("type") DocumentType type,
                                          @Param("vesselIds") Collection<Long> vesselIds);
}
