package com.seastella.troubleshooting.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.ForbiddenException;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
import com.seastella.core.api.event.DomainEventPublisher;
import com.seastella.fleet.api.FleetDirectory;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.identity.api.UserDirectory;
import com.seastella.servicerequest.api.ProblemTypeCatalog;
import com.seastella.servicerequest.api.ProblemTypeCatalog.ProblemTypeRef;
import com.seastella.servicerequest.api.ServiceRequestAction;
import com.seastella.servicerequest.api.ServiceRequestCommands;
import com.seastella.servicerequest.api.ServiceRequestMetrics;
import com.seastella.servicerequest.api.ServiceRequestMetrics.RequestSummary;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import com.seastella.servicerequest.api.TroubleshootingGate;
import com.seastella.troubleshooting.api.TroubleshootingEvents;
import com.seastella.troubleshooting.api.TroubleshootingOutcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The Automated Troubleshooting Assistant (SoW s6.1, TSA-01 to TSA-10).
 *
 * <p>Guided checks run on the request itself, not in a separate bot: starting
 * them moves the request into Troubleshooting, every answer is logged against
 * it, and the request cannot be submitted for approval or escalated until the
 * checks are finished ({@link TroubleshootingGate}).
 *
 * <p>The Captain runs the checks on their own vessel's request. Everyone who
 * can see the request can read the log - the Coordinator reviews it (s6.2), the
 * engineer receives it as context (s6.2), the Captain keeps its history (s8.3).
 */
@Service
class TroubleshootingService implements TroubleshootingGate {

    /** A loop in authored content must not trap the Captain. */
    private static final int MAX_ANSWERS = 50;

    private final TroubleshootingFlowRepository flows;
    private final TroubleshootingStepRepository steps;
    private final TroubleshootingSessionRepository sessions;
    private final TroubleshootingResponseRepository responses;
    private final ServiceRequestCommands requests;
    private final ServiceRequestMetrics metrics;
    private final ProblemTypeCatalog problemTypes;
    private final FleetDirectory fleet;
    private final ScopeResolver scopes;
    private final UserDirectory users;
    private final AuditService audit;
    private final DomainEventPublisher events;
    private final ConversationThread thread;

    TroubleshootingService(TroubleshootingFlowRepository flows, TroubleshootingStepRepository steps,
                           TroubleshootingSessionRepository sessions, TroubleshootingResponseRepository responses,
                           ServiceRequestCommands requests, ServiceRequestMetrics metrics,
                           ProblemTypeCatalog problemTypes, FleetDirectory fleet, ScopeResolver scopes,
                           UserDirectory users, AuditService audit, DomainEventPublisher events,
                           ConversationThread thread) {
        this.flows = flows;
        this.steps = steps;
        this.sessions = sessions;
        this.responses = responses;
        this.requests = requests;
        this.metrics = metrics;
        this.problemTypes = problemTypes;
        this.fleet = fleet;
        this.scopes = scopes;
        this.users = users;
        this.audit = audit;
        this.events = events;
        this.thread = thread;
    }

    // --------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    View view(Long requestId) {
        requests.placementInScope(requestId);                       // 404 outside scope
        RequestSummary request = summary(requestId);
        AccessScope scope = scopes.currentScope();
        boolean captain = scope.role() == Role.CAPTAIN;
        TroubleshootingSession session = sessions.findByServiceRequestId(requestId).orElse(null);

        boolean inTroubleshooting = request.status() == ServiceRequestStatus.REPORTED
                || request.status() == ServiceRequestStatus.TROUBLESHOOTING;
        boolean canStart = captain && session == null && inTroubleshooting;
        boolean canAnswer = captain && session != null
                && session.getStatus() != TroubleshootingSession.Status.COMPLETED
                && request.status() == ServiceRequestStatus.TROUBLESHOOTING;

        List<ProblemTypeOption> options = canStart
                ? problemTypes.forCategory(categoryId(request)).stream()
                        .map(p -> new ProblemTypeOption(p.id(), p.label())).toList()
                : List.of();

        return new View(canStart, canAnswer, options, session == null ? null : sessionView(session));
    }

    // -------------------------------------------------------------------- write

    /** Starts the guided checks and moves the request into Troubleshooting. */
    @Transactional
    void start(Long requestId, Long problemTypeId) {
        AccessScope scope = requireCaptain();
        requests.placementInScope(requestId);
        RequestSummary request = summary(requestId);

        if (request.status() != ServiceRequestStatus.REPORTED && request.status() != ServiceRequestStatus.TROUBLESHOOTING) {
            throw new WorkflowException("Guided checks run before a request goes for approval; this one is "
                    + request.statusLabel().toLowerCase() + ".");
        }
        if (sessions.findByServiceRequestId(requestId).isPresent()) {
            throw new WorkflowException("The guided checks have already been started for " + request.requestNumber() + ".");
        }

        Long categoryId = categoryId(request);
        if (problemTypeId != null) {
            ProblemTypeRef chosen = problemTypes.find(problemTypeId)
                    .filter(p -> p.active() && p.equipmentCategoryId().equals(categoryId))
                    .orElseThrow(() -> new ValidationException("Choose a problem listed for this equipment."));
            requests.recordProblemType(requestId, chosen.id());
        }

        if (request.status() == ServiceRequestStatus.REPORTED) {
            requests.transition(requestId, ServiceRequestAction.START_TROUBLESHOOTING, null, null);
        }

        TroubleshootingFlow flow = flows.candidates(categoryId, problemTypeId).stream().findFirst().orElse(null);
        if (flow == null) {
            // Nothing authored for this equipment yet: the request still moves
            // on, so a gap in content never blocks a Captain from getting help.
            return;
        }
        TroubleshootingStep first = step(flow, flow.getStartStepKey());
        Instant now = Instant.now();
        TroubleshootingSession session = sessions.save(new TroubleshootingSession(
                requestId, request.vesselId(), flow, first.getId(), scope.userId(), now));

        // The checks and the chat are one conversation (CHT-04): it opens here,
        // in ASSISTANT status, and the same thread carries the live chat later.
        Conversation conversation = thread.open(requestId, request.vesselId(), Conversation.ASSISTANT, now);
        thread.system(conversation, "Guided checks started: " + flow.getName() + ".", now);
        thread.assistant(conversation, first.getPrompt(), now);

        audit.record(entry(scope, AuditAction.TROUBLESHOOTING_STARTED, session, request)
                .after(AuditJson.of("flow", flow.getCode(), "flowVersion", flow.getFlowVersion(),
                        "problemTypeId", problemTypeId, "contentSource", flow.getContentSource()))
                .build());
    }

    @Transactional
    void answer(Long requestId, Long stepId, Boolean yes, String note) {
        AccessScope scope = requireCaptain();
        requests.placementInScope(requestId);
        RequestSummary request = summary(requestId);
        TroubleshootingSession session = session(requestId);

        if (yes == null) throw new ValidationException("Answer yes or no.");
        if (session.getStatus() != TroubleshootingSession.Status.IN_PROGRESS
                || request.status() != ServiceRequestStatus.TROUBLESHOOTING) {
            throw new WorkflowException("The guided checks for " + request.requestNumber() + " are already finished.");
        }
        if (stepId == null || !stepId.equals(session.getCurrentStepId())) {
            // A double click or a stale screen: never record an answer to a
            // question that is not the one being asked.
            throw new WorkflowException("That check has already been answered. Reload to see the current one.");
        }
        String trimmedNote = trim(note, 500, "note");

        TroubleshootingStep step = steps.findById(stepId).orElseThrow(() -> NotFoundException.ofResource("Step", stepId));
        Instant now = Instant.now();
        responses.save(new TroubleshootingResponse(session.getId(), step, yes, trimmedNote, scope.userId(), now));

        TroubleshootingOutcome outcome = step.outcome(yes);
        if (outcome == null && responses.countBySessionId(session.getId()) >= MAX_ANSWERS) {
            outcome = TroubleshootingOutcome.UNRESOLVED;
        }
        TroubleshootingStep next = null;
        if (outcome != null) {
            session.reach(outcome);
        } else {
            TroubleshootingFlow flow = flows.findById(session.getFlowId()).orElseThrow();
            next = step(flow, step.nextKey(yes));
            session.moveTo(next.getId());
        }
        sessions.save(session);

        // Answer and next question join the transcript, in order (CHT-04).
        Conversation conversation = thread.open(requestId, request.vesselId(), Conversation.ASSISTANT, now);
        thread.fromUser(conversation, scope.userId(), scope.role().name(),
                (yes ? "Yes" : "No") + (trimmedNote == null ? "" : " — " + trimmedNote), null, now);
        if (next != null) {
            thread.assistant(conversation, next.getPrompt(), now);
        } else {
            thread.system(conversation, "Guided checks finished: " + outcome.label().toLowerCase() + ".", now);
        }

        audit.record(entry(scope, AuditAction.TROUBLESHOOTING_STEP_ANSWERED, session, request)
                .after(AuditJson.of("step", step.getStepKey(), "prompt", step.getPrompt(),
                        "answer", yes ? "YES" : "NO", "note", trimmedNote,
                        "outcome", outcome == null ? null : outcome.name()))
                .build());
    }

    /** Records what was found and done, and closes the session (TSA-06, TSA-07). */
    @Transactional
    void complete(Long requestId, String rootCauseNote, String temporaryFixNote) {
        AccessScope scope = requireCaptain();
        ServiceRequestCommands.Placement placement = requests.placementInScope(requestId);
        RequestSummary request = summary(requestId);
        TroubleshootingSession session = session(requestId);

        if (session.getStatus() != TroubleshootingSession.Status.OUTCOME_REACHED) {
            throw new WorkflowException(session.getStatus() == TroubleshootingSession.Status.COMPLETED
                    ? "The guided checks for " + request.requestNumber() + " are already finished."
                    : "Answer the remaining checks first.");
        }
        String rootCause = trim(rootCauseNote, 1000, "root cause");
        String temporaryFix = trim(temporaryFixNote, 1000, "temporary fix");
        if (session.getOutcome() == TroubleshootingOutcome.TEMPORARY_FIX && temporaryFix == null) {
            throw new ValidationException("Describe the temporary fix applied, so the Coordinator knows what is holding.");
        }

        Instant now = Instant.now();
        session.complete(rootCause, temporaryFix, now);
        sessions.save(session);

        StringBuilder found = new StringBuilder("Findings recorded: ")
                .append(session.getOutcome().label().toLowerCase()).append('.');
        if (rootCause != null) found.append(" Likely cause: ").append(sentence(rootCause));
        if (temporaryFix != null) found.append(" Temporary fix: ").append(sentence(temporaryFix));
        thread.system(thread.open(requestId, request.vesselId(), Conversation.ASSISTANT, now), found.toString(), now);

        audit.record(entry(scope, AuditAction.TROUBLESHOOTING_COMPLETED, session, request)
                .after(AuditJson.of("outcome", session.getOutcome().name(),
                        "rootCause", rootCause, "temporaryFix", temporaryFix))
                .build());
        events.publish(new TroubleshootingEvents.Completed(requestId, request.requestNumber(),
                session.getOutcome(), scope.userId(), placement.organizationId(), request.vesselId(), now));
    }

    // --------------------------------------------------------------------- gate

    @Override
    @Transactional(readOnly = true)
    public String blockingReason(Long serviceRequestId) {
        return sessions.findByServiceRequestId(serviceRequestId)
                .filter(s -> s.getStatus() != TroubleshootingSession.Status.COMPLETED)
                .map(s -> s.getStatus() == TroubleshootingSession.Status.IN_PROGRESS
                        ? "Finish the guided checks first."
                        : "Record what the guided checks found, then continue.")
                .orElse(null);
    }

    // ---------------------------------------------------------------- internals

    private SessionView sessionView(TroubleshootingSession s) {
        TroubleshootingFlow flow = flows.findById(s.getFlowId()).orElseThrow();
        List<TroubleshootingResponse> answered = responses.findBySessionIdOrderByIdAsc(s.getId());
        Map<Long, UserDirectory.UserRef> people = users.findAll(
                java.util.stream.Stream.concat(
                        answered.stream().map(TroubleshootingResponse::getAnsweredByUserId),
                        java.util.stream.Stream.of(s.getStartedByUserId())).toList());

        List<Answer> log = new java.util.ArrayList<>();
        for (int i = 0; i < answered.size(); i++) {
            TroubleshootingResponse r = answered.get(i);
            log.add(new Answer(i + 1, r.getPrompt(), r.getResponseValue(), r.getNote(),
                    name(people, r.getAnsweredByUserId()), r.getAnsweredAt()));
        }

        CurrentStep current = null;
        if (s.getStatus() == TroubleshootingSession.Status.IN_PROGRESS && s.getCurrentStepId() != null) {
            TroubleshootingStep step = steps.findById(s.getCurrentStepId()).orElseThrow();
            current = new CurrentStep(step.getId(), answered.size() + 1, step.getPrompt(), step.getHelpText());
        }

        return new SessionView(flow.getName(), flow.getFlowVersion(),
                TroubleshootingFlow.SAMPLE.equals(flow.getContentSource()),
                s.getStatus().name(),
                s.getOutcome() == null ? null : s.getOutcome().name(),
                s.getOutcome() == null ? null : s.getOutcome().label(),
                s.getRootCauseNote(), s.getTemporaryFixNote(),
                name(people, s.getStartedByUserId()), s.getStartedAt(), s.getCompletedAt(),
                current, log);
    }

    private static String sentence(String text) {
        String t = text.strip();
        return t.matches(".*[.!?]$") ? t : t + ".";
    }

    private AccessScope requireCaptain() {
        AccessScope scope = scopes.currentScope();
        if (scope.role() != Role.CAPTAIN) {
            // RBAC matrix: the Captain runs the assistant on their own request.
            throw ForbiddenException.ofAction("run the guided checks");
        }
        return scope;
    }

    private RequestSummary summary(Long requestId) {
        return metrics.summary(requestId).orElseThrow(() -> NotFoundException.ofResource("ServiceRequest", requestId));
    }

    private TroubleshootingSession session(Long requestId) {
        return sessions.findByServiceRequestId(requestId)
                .orElseThrow(() -> new WorkflowException("The guided checks have not been started for this request."));
    }

    private Long categoryId(RequestSummary request) {
        return request.categoryCode() == null ? null
                : fleet.equipmentCategoryIdByCode(request.categoryCode()).orElse(null);
    }

    private TroubleshootingStep step(TroubleshootingFlow flow, String key) {
        return steps.findByFlowIdAndStepKey(flow.getId(), key)
                .orElseThrow(() -> new IllegalStateException("Flow " + flow.getCode() + " has no step " + key));
    }

    private static AuditEntry.Builder entry(AccessScope scope, String action, TroubleshootingSession session,
                                            RequestSummary request) {
        return AuditEntry.builder()
                .actor(scope.userId(), scope.role().name())
                .action(action)
                .entity("ServiceRequest", session.getServiceRequestId())
                .scope(null, request.vesselId());
    }

    private static String name(Map<Long, UserDirectory.UserRef> people, Long id) {
        UserDirectory.UserRef ref = people.get(id);
        return ref == null ? null : ref.fullName();
    }

    private static String trim(String value, int max, String what) {
        if (value == null) return null;
        String t = value.trim();
        if (t.isEmpty()) return null;
        if (t.length() > max) throw new ValidationException("Keep the " + what + " under " + max + " characters.");
        return t;
    }

    // ------------------------------------------------------------------- views

    record View(boolean canStart, boolean canAnswer, List<ProblemTypeOption> problemTypes, SessionView session) {}

    record ProblemTypeOption(Long id, String label) {}

    record SessionView(String flowName, int flowVersion, boolean sampleContent, String status,
                       String outcome, String outcomeLabel, String rootCauseNote, String temporaryFixNote,
                       String startedBy, Instant startedAt, Instant completedAt,
                       CurrentStep currentStep, List<Answer> answers) {}

    record CurrentStep(Long id, int number, String prompt, String helpText) {}

    record Answer(int number, String prompt, String answer, String note, String answeredBy, Instant answeredAt) {}
}
