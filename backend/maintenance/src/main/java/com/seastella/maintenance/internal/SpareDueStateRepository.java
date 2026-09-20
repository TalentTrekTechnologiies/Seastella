package com.seastella.maintenance.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SpareDueStateRepository extends JpaRepository<SpareDueState, Long> {

    List<SpareDueState> findBySpareIdIn(Collection<Long> spareIds);
}
