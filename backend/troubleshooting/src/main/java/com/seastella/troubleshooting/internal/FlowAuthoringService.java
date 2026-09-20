package com.seastella.troubleshooting.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
import com.seastella.fleet.api.FleetDirectory;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.identity.api.UserDirectory;
import com.seastella.servicerequest.api.ProblemTypeCatalog;
import com.seastella.troubleshooting.api.TroubleshootingOutcome;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Authoring guided checks (SoW s13: "Seastella can add new Spare/problem
 * combinations without code changes"; TSA-03). Platform Admin only - the
 * controller gates the role.
 *
 * <p>The lifecycle protects Captains who are mid-way through a set of checks:
 * content is edited only as a draft; publishing a draft makes it the version
 * new requests get and retires the previous one; a session keeps the version
 * it started with. Nothing that a session could point at is ever changed or
 * deleted.
 *
 * <p>One set of checks per target - an equipment category and problem type,
 * a category for any problem, or the general fallback - so which checks a
 * Captain gets is never ambiguous.
 */
@Service
class FlowAuthoringService {

    private final TroubleshootingFlowRepository flows;
    private final TroubleshootingStepRepository steps;
    private final TroubleshootingSessionRepository sessions;
    private final FleetDirectory fleet;
    private final ProblemTypeCatalog problemTypes;
    private final UserDirectory users;
    private final ScopeResolver scopes;
    private final AuditService audit;
    private final EntityManager entityManager;

    FlowAuthoringService(TroubleshootingFlowRepository flows, TroubleshootingStepRepository steps,
                         TroubleshootingSessionRepository sessions, FleetDirectory fleet,
                         ProblemTypeCatalog problemTypes, UserDirectory users, ScopeResolver scopes,
                         AuditService audit, EntityManager entityManager) {
        this.entityManager = entityManager;
        this.flows = flows;
        this.steps = steps;
        this.sessions = sessions;
        this.fleet = fleet;
        this.problemTypes = problemTypes;
        this.users = users;
        this.scopes = scopes;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ inputs

    record StepInput(String key, String prompt, String helpText, BranchInput yes, BranchInput no) {}

    record BranchInput(String nextKey, String outcome) {}

    record CreateInput(String name, Long equipmentCategoryId, Long problemTypeId, String firstQuestion) {}

    record DraftInput(Long version, String name, Long equipmentCategoryId, Long problemTypeId,
                      Boolean sampleContent, String startStepKey, List<StepInput> steps) {}

    // ------------------------------------------------------------------- views

    record Target(Long equipmentCategoryId, String categoryName, Long problemTypeId, String problemTypeLabel,
                  boolean problemTypeActive, String scope) {}

    record VersionRef(Long id, int version, String status, Instant publishedAt, Instant retiredAt,
                      long requestCount) {}

    record FlowSummary(String code, String name, Target target, boolean sampleContent,
                       VersionRef published, VersionRef draft, int versionCount) {}

    record StepView(String key, int number, String prompt, String helpText, BranchInput yes, BranchInput no) {}

    record FlowDetail(Long id, String code, String name, int flowVersion, String status, long version,
                      boolean sampleContent, Target target, String startStepKey, List<StepView> steps,
                      Instant publishedAt, String publishedBy, long requestCount, long openRequestCount,
                      Long draftId, List<VersionRef> versions, List<String> problems) {}

    // -------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    List<FlowSummary> list() {
        Map<Long, FleetDirectory.CategoryRef> categories = categories();
        Map<String, List<TroubleshootingFlow>> byCode = flows.findAllByOrderByCodeAscFlowVersionDesc().stream()
                .collect(Collectors.groupingBy(TroubleshootingFlow::getCode, LinkedHashMap::new, Collectors.toList()));

        List<FlowSummary> result = new ArrayList<>();
        byCode.forEach((code, versions) -> {
            TroubleshootingFlow draft = versions.stream().filter(TroubleshootingFlow::isDraft).findFirst().orElse(null);
            TroubleshootingFlow published = versions.stream().filter(TroubleshootingFlow::isPublished).findFirst().orElse(null);
            TroubleshootingFlow shown = draft != null ? draft : published != null ? published : versions.get(0);
            result.add(new FlowSummary(code, shown.getName(), target(shown, categories),
                    TroubleshootingFlow.SAMPLE.equals(shown.getContentSource()),
                    published == null ? null : ref(published), draft == null ? null : ref(draft), versions.size()));
        });
        result.sort(Comparator
                .comparing((FlowSummary s) -> s.target().categoryName() == null ? "￿" : s.target().categoryName())
                .thenComparing(s -> s.target().problemTypeLabel() == null ? "" : s.target().problemTypeLabel())
                .thenComparing(FlowSummary::name));
        return result;
    }

    @Transactional(readOnly = true)
    FlowDetail detail(Long flowId) {
        TroubleshootingFlow flow = load(flowId);
        Map<Long, FleetDirectory.CategoryRef> categories = categories();
        List<TroubleshootingStep> stepRows = steps.findByFlowIdOrderByDisplayOrderAsc(flow.getId());

        List<StepView> stepViews = new ArrayList<>();
        for (int i = 0; i < stepRows.size(); i++) {
            TroubleshootingStep s = stepRows.get(i);
            stepViews.add(new StepView(s.getStepKey(), i + 1, s.getPrompt(), s.getHelpText(),
                    branch(s.getYesNextKey(), s.getYesOutcome()), branch(s.getNoNextKey(), s.getNoOutcome())));
        }

        List<TroubleshootingFlow> versions = flows.findByCodeOrderByFlowVersionDesc(flow.getCode());
        Long draftId = versions.stream().filter(TroubleshootingFlow::isDraft).map(TroubleshootingFlow::getId)
                .findFirst().orElse(null);
        String publishedBy = flow.getPublishedByUserId() == null ? null
                : Optional.ofNullable(users.findAll(List.of(flow.getPublishedByUserId())).get(flow.getPublishedByUserId()))
                        .map(UserDirectory.UserRef::fullName).orElse(null);

        List<String> problems = flow.isDraft() ? problems(flow, stepRows) : List.of();

        return new FlowDetail(flow.getId(), flow.getCode(), flow.getName(), flow.getFlowVersion(),
                flow.getStatus().name(), flow.getVersion(),
                TroubleshootingFlow.SAMPLE.equals(flow.getContentSource()), target(flow, categories),
                flow.getStartStepKey(), stepViews, flow.getPublishedAt(), publishedBy,
                sessions.countByFlowId(flow.getId()),
                sessions.countByFlowIdAndStatusNot(flow.getId(), TroubleshootingSession.Status.COMPLETED),
                draftId, versions.stream().map(this::ref).toList(), problems);
    }

    // ------------------------------------------------------------------- write

    /** A new set of checks, as a draft with its first question. */
    @Transactional
    Long create(CreateInput input) {
        if (input == null) throw new ValidationException("Name the checks and choose what they are for.");
        String name = name(input.name());
        TargetIds target = target(input.equipmentCategoryId(), input.problemTypeId());
        assertTargetFree(target, null);
        String question = input.firstQuestion() == null ? "" : input.firstQuestion().trim();
        if (question.isEmpty()) throw new ValidationException("Write the first question the Captain is asked.");
        if (question.length() > FlowRules.MAX_PROMPT) {
            throw new ValidationException("Keep the question under " + FlowRules.MAX_PROMPT + " characters.");
        }

        TroubleshootingFlow flow = flows.save(new TroubleshootingFlow(target.categoryId(), target.problemTypeId(),
                uniqueCode(target), name, 1, TroubleshootingFlow.SEASTELLA, "s1"));
        // Yes resolves, no is unresolved: a sensible start the author then extends.
        steps.save(new TroubleshootingStep(flow.getId(), "s1", 1, question, null,
                null, TroubleshootingOutcome.RESOLVED, null, TroubleshootingOutcome.UNRESOLVED));

        record(AuditAction.CHECKS_DRAFT_SAVED, flow, AuditJson.of("code", flow.getCode(), "flowVersion", 1,
                "name", name, "created", true));
        return flow.getId();
    }

    /**
     * Replaces a draft's content. {@code version} is the draft's version as the
     * author loaded it: if someone else saved in between, this save is refused
     * rather than silently overwriting their work.
     */
    @Transactional
    void saveDraft(Long flowId, DraftInput input) {
        TroubleshootingFlow flow = load(flowId);
        if (!flow.isDraft()) {
            throw new WorkflowException("Published checks cannot be changed. Create a new draft to edit them.");
        }
        if (input == null || input.version() == null || input.version() != flow.getVersion()) {
            throw new WorkflowException("These checks were saved by someone else after you opened them. "
                    + "Reload to see the latest version, then make your change again.");
        }
        String name = name(input.name());
        TargetIds target = target(input.equipmentCategoryId(), input.problemTypeId());
        assertTargetFree(target, flow.getCode());

        List<FlowRules.Step> ruleSteps = toRuleSteps(input.steps());
        List<String> problems = FlowRules.structural(input.startStepKey(), ruleSteps);
        if (!problems.isEmpty()) throw new ValidationException(summarise(problems));

        steps.deleteByFlowId(flow.getId());
        steps.flush();
        int order = 1;
        for (FlowRules.Step s : ruleSteps) {
            steps.save(new TroubleshootingStep(flow.getId(), s.key(), order++, s.prompt().trim(), blankToNull(s.helpText()),
                    s.yes().nextKey(), s.yes().outcome(), s.no().nextKey(), s.no().outcome()));
        }
        flow.edit(name, target.categoryId(), target.problemTypeId(),
                Boolean.TRUE.equals(input.sampleContent()) ? TroubleshootingFlow.SAMPLE : TroubleshootingFlow.SEASTELLA,
                input.startStepKey());
        flows.save(flow);
        // The version moves with every save, even when only the steps changed, so
        // a save racing this one fails instead of silently overwriting it.
        entityManager.lock(flow, LockModeType.OPTIMISTIC_FORCE_INCREMENT);

        record(AuditAction.CHECKS_DRAFT_SAVED, flow, AuditJson.of("code", flow.getCode(),
                "flowVersion", flow.getFlowVersion(), "name", name, "steps", ruleSteps.size()));
    }

    /** A draft of the next version, copied from this one. If a draft already exists, that draft. */
    @Transactional
    Long newDraft(Long flowId) {
        TroubleshootingFlow source = load(flowId);
        List<TroubleshootingFlow> versions = flows.findByCodeOrderByFlowVersionDesc(source.getCode());
        Optional<TroubleshootingFlow> existing = versions.stream().filter(TroubleshootingFlow::isDraft).findFirst();
        if (existing.isPresent()) return existing.get().getId();

        int next = versions.get(0).getFlowVersion() + 1;
        TroubleshootingFlow draft = flows.save(new TroubleshootingFlow(source.getEquipmentCategoryId(),
                source.getProblemTypeId(), source.getCode(), source.getName(), next, source.getContentSource(),
                source.getStartStepKey()));
        for (TroubleshootingStep s : steps.findByFlowIdOrderByDisplayOrderAsc(source.getId())) {
            steps.save(new TroubleshootingStep(draft.getId(), s.getStepKey(), s.getDisplayOrder(), s.getPrompt(),
                    s.getHelpText(), s.getYesNextKey(), s.getYesOutcome(), s.getNoNextKey(), s.getNoOutcome()));
        }
        record(AuditAction.CHECKS_DRAFT_SAVED, draft, AuditJson.of("code", draft.getCode(), "flowVersion", next,
                "copiedFromVersion", source.getFlowVersion()));
        return draft.getId();
    }

    /**
     * Makes a draft the checks Captains get. The previous published version is
     * retired; requests already part-way through it finish on it.
     */
    @Transactional
    void publish(Long flowId, Long version) {
        TroubleshootingFlow flow = load(flowId);
        if (!flow.isDraft()) {
            throw new WorkflowException("Version " + flow.getFlowVersion() + " is already "
                    + flow.getStatus().name().toLowerCase() + ".");
        }
        if (version == null || version != flow.getVersion()) {
            throw new WorkflowException("These checks changed after you opened them. Reload and review before publishing.");
        }
        List<String> problems = problems(flow, steps.findByFlowIdOrderByDisplayOrderAsc(flow.getId()));
        if (!problems.isEmpty()) {
            throw new ValidationException("These checks cannot be published yet. " + summarise(problems));
        }
        assertTargetFree(new TargetIds(flow.getEquipmentCategoryId(), flow.getProblemTypeId()), flow.getCode());

        Instant now = Instant.now();
        Integer replaced = null;
        Optional<TroubleshootingFlow> current = flows.findByCodeAndStatus(flow.getCode(), TroubleshootingFlow.Status.PUBLISHED);
        if (current.isPresent()) {
            current.get().retire(now);
            // Retire first and flush, so there is never a moment with two live versions.
            flows.saveAndFlush(current.get());
            replaced = current.get().getFlowVersion();
        }
        AccessScope scope = scopes.currentScope();
        flow.publish(scope.userId(), now);
        flows.saveAndFlush(flow);

        Target target = target(flow, categories());
        record(AuditAction.CHECKS_PUBLISHED, flow, AuditJson.of("code", flow.getCode(),
                "flowVersion", flow.getFlowVersion(), "name", flow.getName(),
                "category", target.categoryName(), "problemType", target.problemTypeLabel(),
                "steps", steps.countByFlowId(flow.getId()), "contentSource", flow.getContentSource(),
                "replacedVersion", replaced));
    }

    /**
     * Withdraws published checks without a replacement. New requests fall back
     * to the next most general checks; requests already running them finish.
     */
    @Transactional
    void retire(Long flowId) {
        TroubleshootingFlow flow = load(flowId);
        if (!flow.isPublished()) {
            throw new WorkflowException("Only published checks can be withdrawn; this version is "
                    + flow.getStatus().name().toLowerCase() + ".");
        }
        flow.retire(Instant.now());
        flows.save(flow);
        record(AuditAction.CHECKS_RETIRED, flow, AuditJson.of("code", flow.getCode(),
                "flowVersion", flow.getFlowVersion(), "name", flow.getName()));
    }

    /** Deletes a draft. Nothing can refer to a draft, so nothing else changes. */
    @Transactional
    void discard(Long flowId) {
        TroubleshootingFlow flow = load(flowId);
        if (!flow.isDraft()) {
            throw new WorkflowException("Only a draft can be discarded. Published checks can be withdrawn instead.");
        }
        record(AuditAction.CHECKS_DRAFT_DISCARDED, flow, AuditJson.of("code", flow.getCode(),
                "flowVersion", flow.getFlowVersion(), "name", flow.getName()));
        steps.deleteByFlowId(flow.getId());
        flows.delete(flow);
    }

    // --------------------------------------------------------------- internals

    private record TargetIds(Long categoryId, Long problemTypeId) {}

    private TargetIds target(Long categoryId, Long problemTypeId) {
        if (categoryId == null && problemTypeId != null) {
            throw new ValidationException("Choose the equipment for this problem type.");
        }
        if (categoryId != null && !categories().containsKey(categoryId)) {
            throw NotFoundException.ofResource("EquipmentCategory", categoryId);
        }
        if (problemTypeId != null) {
            ProblemTypeCatalog.ProblemTypeRef p = problemTypes.find(problemTypeId)
                    .orElseThrow(() -> NotFoundException.ofResource("ProblemType", problemTypeId));
            if (!p.equipmentCategoryId().equals(categoryId)) {
                throw new ValidationException("That problem type belongs to different equipment.");
            }
        }
        return new TargetIds(categoryId, problemTypeId);
    }

    /** No other set of checks - draft or published - already covers this target. */
    private void assertTargetFree(TargetIds target, String exceptCode) {
        flows.findAll().stream()
                .filter(f -> f.getStatus() != TroubleshootingFlow.Status.RETIRED)
                .filter(f -> !f.getCode().equals(exceptCode))
                .filter(f -> Objects.equals(f.getEquipmentCategoryId(), target.categoryId())
                        && Objects.equals(f.getProblemTypeId(), target.problemTypeId()))
                .findFirst()
                .ifPresent(other -> {
                    throw new WorkflowException("\"" + other.getName() + "\" already covers "
                            + describe(target(other, categories())) + ". Edit those checks instead.");
                });
    }

    private List<String> problems(TroubleshootingFlow flow, List<TroubleshootingStep> stepRows) {
        List<FlowRules.Step> ruleSteps = stepRows.stream()
                .map(s -> new FlowRules.Step(s.getStepKey(), s.getPrompt(), s.getHelpText(),
                        new FlowRules.Branch(s.getYesNextKey(), s.getYesOutcome()),
                        new FlowRules.Branch(s.getNoNextKey(), s.getNoOutcome())))
                .toList();
        List<String> problems = new ArrayList<>(FlowRules.publishable(flow.getStartStepKey(), ruleSteps));
        if (flow.getProblemTypeId() != null) {
            problemTypes.find(flow.getProblemTypeId()).filter(p -> !p.active()).ifPresent(p ->
                    problems.add("The problem type \"" + p.label() + "\" is retired, so no Captain can choose it. "
                            + "Bring it back, or aim these checks at the whole equipment."));
        }
        return problems;
    }

    private static List<FlowRules.Step> toRuleSteps(List<StepInput> input) {
        if (input == null) return List.of();
        List<FlowRules.Step> result = new ArrayList<>();
        for (StepInput s : input) {
            if (s == null) {
                result.add(null);
                continue;
            }
            result.add(new FlowRules.Step(s.key(), s.prompt(), s.helpText(), branch(s.yes()), branch(s.no())));
        }
        return result;
    }

    private static FlowRules.Branch branch(BranchInput b) {
        if (b == null) return null;
        TroubleshootingOutcome outcome = null;
        if (b.outcome() != null && !b.outcome().isBlank()) {
            try {
                outcome = TroubleshootingOutcome.valueOf(b.outcome());
            } catch (IllegalArgumentException e) {
                throw new ValidationException("An answer can end as Resolved, Temporary fix or Not resolved.");
            }
        }
        String next = b.nextKey() == null || b.nextKey().isBlank() ? null : b.nextKey();
        return new FlowRules.Branch(next, outcome);
    }

    private static BranchInput branch(String nextKey, TroubleshootingOutcome outcome) {
        return new BranchInput(nextKey, outcome == null ? null : outcome.name());
    }

    private TroubleshootingFlow load(Long flowId) {
        return flows.findById(flowId).orElseThrow(() -> NotFoundException.ofResource("TroubleshootingFlow", flowId));
    }

    private Map<Long, FleetDirectory.CategoryRef> categories() {
        return fleet.equipmentCategories().stream()
                .collect(Collectors.toMap(FleetDirectory.CategoryRef::id, Function.identity()));
    }

    private Target target(TroubleshootingFlow f, Map<Long, FleetDirectory.CategoryRef> categories) {
        FleetDirectory.CategoryRef category = f.getEquipmentCategoryId() == null ? null : categories.get(f.getEquipmentCategoryId());
        ProblemTypeCatalog.ProblemTypeRef problem = f.getProblemTypeId() == null ? null
                : problemTypes.find(f.getProblemTypeId()).orElse(null);
        String scope = f.getProblemTypeId() != null ? "PROBLEM_TYPE" : f.getEquipmentCategoryId() != null ? "EQUIPMENT" : "GENERAL";
        return new Target(f.getEquipmentCategoryId(), category == null ? null : category.name(),
                f.getProblemTypeId(), problem == null ? null : problem.label(), problem == null || problem.active(), scope);
    }

    private static String describe(Target t) {
        return switch (t.scope()) {
            case "PROBLEM_TYPE" -> t.categoryName() + ": " + t.problemTypeLabel();
            case "EQUIPMENT" -> "any problem on " + t.categoryName();
            default -> "the general checks for any equipment";
        };
    }

    private VersionRef ref(TroubleshootingFlow f) {
        return new VersionRef(f.getId(), f.getFlowVersion(), f.getStatus().name(), f.getPublishedAt(), f.getRetiredAt(),
                sessions.countByFlowId(f.getId()));
    }

    /** CHECKS + target, e.g. CHECKS-RADAR_NO_ECHO; unique across all flows. */
    private String uniqueCode(TargetIds target) {
        String base = target.problemTypeId() != null
                ? problemTypes.find(target.problemTypeId()).map(ProblemTypeCatalog.ProblemTypeRef::code).orElse("PROBLEM")
                : target.categoryId() != null ? categories().get(target.categoryId()).code() : "GENERAL";
        base = "CHECKS-" + base;
        if (base.length() > 55) base = base.substring(0, 55);
        String code = base;
        for (int n = 2; flows.existsByCode(code); n++) {
            code = base + "-" + n;
        }
        return code;
    }

    private static String name(String raw) {
        String t = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) throw new ValidationException("Name these checks, e.g. \"Radar: no echoes\".");
        if (t.length() > 160) throw new ValidationException("Keep the name under 160 characters.");
        return t;
    }

    private static String summarise(List<String> problems) {
        return problems.get(0) + (problems.size() > 1 ? " (" + (problems.size() - 1) + " more to fix)" : "");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void record(String action, TroubleshootingFlow flow, String after) {
        AccessScope scope = scopes.currentScope();
        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role().name())
                .action(action)
                .entity("TroubleshootingFlow", flow.getId())
                .after(after)
                .build());
    }
}
