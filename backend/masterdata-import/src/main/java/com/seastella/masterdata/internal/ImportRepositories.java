package com.seastella.masterdata.internal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    List<ImportBatch> findAllByOrderByIdDesc(Pageable page);

    List<ImportBatch> findByUploadedByUserIdInOrderByIdDesc(List<Long> userIds, Pageable page);
}
