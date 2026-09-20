package com.seastella.fleet.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RunningHourReadingRepository extends JpaRepository<RunningHourReading, Long> {

    Optional<RunningHourReading> findFirstBySpareIdOrderByReadingDateDescIdDesc(Long spareId);

    List<RunningHourReading> findTop24BySpareIdOrderByReadingDateDescIdDesc(Long spareId);
}
