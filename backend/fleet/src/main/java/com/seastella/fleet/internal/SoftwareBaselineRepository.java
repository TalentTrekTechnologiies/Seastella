package com.seastella.fleet.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SoftwareBaselineRepository extends JpaRepository<SoftwareBaseline, Long> {

    Optional<SoftwareBaseline> findByMatchKey(String matchKey);

    List<SoftwareBaseline> findAllByOrderByMakeAscModelAsc();
}
