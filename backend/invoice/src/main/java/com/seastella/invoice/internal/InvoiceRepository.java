package com.seastella.invoice.internal;

import com.seastella.invoice.api.InvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    /** The gate query. Existence of an ACCEPTED invoice for this request. */
    boolean existsByServiceRequestIdAndStatus(Long serviceRequestId, InvoiceStatus status);

    Optional<Invoice> findByServiceRequestIdAndStatus(Long serviceRequestId, InvoiceStatus status);

    List<Invoice> findByServiceRequestIdOrderByCreatedAtDesc(Long serviceRequestId);

    List<Invoice> findByVesselIdInAndStatus(Set<Long> vesselIds, InvoiceStatus status);

    @Query("select i.status, count(i), coalesce(sum(i.amount), 0) from Invoice i where i.vesselId in :ids group by i.status")
    List<Object[]> summariseByStatus(@Param("ids") Set<Long> vesselIds);

    @Query("select i.status, count(i), coalesce(sum(i.amount), 0) from Invoice i group by i.status")
    List<Object[]> summariseByStatusPlatformWide();

    @Query("select coalesce(sum(i.amount), 0) from Invoice i where i.vesselId in :ids and i.status = :status")
    BigDecimal totalAmount(@Param("ids") Set<Long> vesselIds, @Param("status") InvoiceStatus status);

    @Query("select count(i) from Invoice i where i.vesselId in :ids and i.status = :status")
    long countByStatus(@Param("ids") Set<Long> vesselIds, @Param("status") InvoiceStatus status);

    boolean existsByInvoiceNumber(String invoiceNumber);
}
