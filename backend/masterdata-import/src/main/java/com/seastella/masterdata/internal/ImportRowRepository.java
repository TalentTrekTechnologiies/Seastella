package com.seastella.masterdata.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface ImportRowRepository extends JpaRepository<ImportRow, Long> {

    List<ImportRow> findByBatchIdOrderByRowNumberAsc(Long batchId);
}
