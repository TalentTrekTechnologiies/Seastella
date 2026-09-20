package com.seastella.servicerequest.internal;

import com.seastella.servicerequest.api.ProblemTypeCatalog;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Component
class DefaultProblemTypeCatalog implements ProblemTypeCatalog {

    private final ProblemTypeRepository problemTypes;

    DefaultProblemTypeCatalog(ProblemTypeRepository problemTypes) {
        this.problemTypes = problemTypes;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProblemTypeRef> forCategory(Long equipmentCategoryId) {
        if (equipmentCategoryId == null) return List.of();
        return problemTypes.findByEquipmentCategoryIdAndActiveTrueOrderByDisplayOrderAsc(equipmentCategoryId).stream()
                .map(DefaultProblemTypeCatalog::toRef)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProblemTypeRef> find(Long problemTypeId) {
        if (problemTypeId == null) return Optional.empty();
        return problemTypes.findById(problemTypeId).map(DefaultProblemTypeCatalog::toRef);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProblemTypeRef> findByCode(String code) {
        return problemTypes.findByCode(code).map(DefaultProblemTypeCatalog::toRef);
    }

    private static ProblemTypeRef toRef(ProblemType p) {
        return new ProblemTypeRef(p.getId(), p.getEquipmentCategoryId(), p.getCode(), p.getLabel(), p.isActive());
    }
}
