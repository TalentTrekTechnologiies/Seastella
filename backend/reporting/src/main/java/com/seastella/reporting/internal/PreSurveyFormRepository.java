package com.seastella.reporting.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface PreSurveyFormRepository extends JpaRepository<PreSurveyForm, Long> {

    List<PreSurveyForm> findByVesselIdOrderByIdDesc(Long vesselId);
}
