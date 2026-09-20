package com.seastella.servicerequest.api;

import java.util.List;
import java.util.Optional;

/** The problem types a Captain chooses from, per equipment category (SoW s6.1). */
public interface ProblemTypeCatalog {

    /** Active problem types only - what a Captain may choose today. */
    List<ProblemTypeRef> forCategory(Long equipmentCategoryId);

    /** Any problem type, retired or not, for labelling what already refers to it. */
    Optional<ProblemTypeRef> find(Long problemTypeId);

    Optional<ProblemTypeRef> findByCode(String code);

    record ProblemTypeRef(Long id, Long equipmentCategoryId, String code, String label, boolean active) {}
}
