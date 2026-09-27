package com.seastella.servicerequest.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.ForbiddenException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
import com.seastella.fleet.api.RequestAttachments;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.identity.api.UserDirectory;
import com.seastella.servicerequest.api.ServiceRequestAction;
import com.seastella.servicerequest.api.ServiceRequestEvents;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The engineer's job log (SoW s6.3): from arriving aboard to leaving, what was
 * done and when - so the job has a record between "start work" and the
 * completion report, and the time on it can be read off afterwards.
 *
 * <p>Written by the engineer assigned to the job, while it is theirs; the
 * workflow adds "work started" and "report submitted" itself. Read by everyone
 * who can see the request - it carries no cost, so the Captain, who had the
 * engineer aboard, reads it too.
 */
@Service
class JobLogService {

    private static final Set<ServiceRequestStatus> OPEN_FOR_LOG = EnumSet.of(
            ServiceRequestStatus.ENGINEER_ASSIGNED, ServiceRequestStatus.IN_PROGRESS,
            ServiceRequestStatus.COMPLETION_REPORTED);
    private static final Set<JobLogEntry.Kind> NEEDS_NOTE = EnumSet.of(
            JobLogEntry.Kind.UPDATE, JobLogEntry.Kind.WAITING);
    /** How long after the fact an entry may still be logged. */
    private static final Duration BACKDATE_LIMIT = Duration.ofDays(30);
    private static final int MAX_NOTE = 2000;

    private final JobLogEntryRepository entries;
    private final DefaultServiceRequestCommands requests;
    private final RequestAttachments attachments;
    private final ScopeResolver scopes;
    private final UserDirectory users;
    private final AuditService audit;

    JobLogService(JobLogEntryRepository entries, DefaultServiceRequestCommands requests,
                  RequestAttachments attachments, ScopeResolver scopes, UserDirectory users, AuditService audit) {
        this.entries = entries;
        this.requests = requests;
        this.attachments = attachments;
        this.scopes = scopes;
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    JobLog view(Long requestId) {
        AccessScope scope = scopes.currentScope();
        ServiceRequest request = requests.loadInScope(requestId, scope);
        List<JobLogEntry> found = entries.findByServiceRequestIdOrderByOccurredAtAscIdAsc(requestId);

        Map<Long, UserDirectory.UserRef> people = users.findAll(
                found.stream().map(JobLogEntry::getAuthorUserId).distinct().toList());
        Map<Long, RequestAttachments.AttachmentRef> files = attachments.describe(
                found.stream().map(JobLogEntry::getDocumentId).filter(Objects::nonNull).toList());

        List<EntryView> views = found.stream().map(e -> {
            UserDirectory.UserRef who = people.get(e.getAuthorUserId());
            RequestAttachments.AttachmentRef file = e.getDocumentId() == null ? null : files.get(e.getDocumentId());
            return new EntryView(e.getId(), e.getKind().name(), e.getOccurredAt(), e.getNote(),
                    who == null ? null : who.fullName(), who == null ? null : who.role().name(),
                    e.isAutomatic(), e.getCreatedAt(),
                    file == null ? null : new PhotoView(file.documentId(), file.fileName(), file.contentType(),
                            file.sizeBytes(), file.isImage(), file.isVideo()));
        }).toList();

        return new JobLog(canWrite(scope, request), views);
    }

    @Transactional
    EntryView add(Long requestId, String kind, Instant occurredAt, String note,
                  String fileName, byte[] content) {
        AccessScope scope = scopes.currentScope();
        ServiceRequest request = requests.loadInScope(requestId, scope);
        if (scope.role() != Role.SERVICE_ENGINEER || !Objects.equals(request.getAssignedEngineerUserId(), scope.userId())) {
            throw ForbiddenException.ofAction("write in this job's log");
        }
        if (!OPEN_FOR_LOG.contains(request.getStatus())) {
            throw new WorkflowException("The job log is written while the job is assigned to you and until it is "
                    + "completed; " + request.getRequestNumber() + " is " + request.getStatus().label().toLowerCase() + ".");
        }

        JobLogEntry.Kind what;
        try {
            what = JobLogEntry.Kind.valueOf(kind == null ? "" : kind.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Say what happened: arrived, update, waiting, resumed, finished or left.");
        }
        if (what == JobLogEntry.Kind.REPORTED) {
            throw new ValidationException("The report entry is added when the completion report is submitted.");
        }

        Instant now = Instant.now();
        Instant when = occurredAt == null ? now : occurredAt;
        if (when.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw new ValidationException("The time cannot be in the future.");
        }
        if (when.isBefore(now.minus(BACKDATE_LIMIT))) {
            throw new ValidationException("Entries can be logged up to 30 days after the fact.");
        }

        String text = note == null ? null : note.strip();
        if (text != null && text.isEmpty()) text = null;
        if (text != null && text.length() > MAX_NOTE) {
            throw new ValidationException("Keep the note under " + MAX_NOTE + " characters.");
        }
        boolean hasFile = content != null && content.length > 0;
        if (NEEDS_NOTE.contains(what) && text == null && !hasFile) {
            throw new ValidationException(what == JobLogEntry.Kind.WAITING
                    ? "Say what the job is waiting for." : "Say what was done.");
        }

        // "Work started" from the log is the start itself, at the time the engineer gives:
        // it starts the job if it is not started yet, and otherwise replaces the time the
        // workflow stamped when the button was pressed.
        if (what == JobLogEntry.Kind.STARTED) {
            List<JobLogEntry> started = entries.findByServiceRequestIdOrderByOccurredAtAscIdAsc(requestId).stream()
                    .filter(e -> e.getKind() == JobLogEntry.Kind.STARTED).toList();
            if (started.stream().anyMatch(e -> !e.isAutomatic())) {
                throw new WorkflowException("Work start is already logged. Use \"Work resumed\" after a break.");
            }
            entries.deleteAll(started);
        }

        Long documentId = null;
        if (hasFile) {
            documentId = attachments.attach(request.getVesselId(), requestId, fileName, content,
                    text == null ? label(what) : text).documentId();
        }

        JobLogEntry entry = entries.save(new JobLogEntry(requestId, request.getVesselId(), scope.userId(), what,
                when, text, documentId, false));
        if (what == JobLogEntry.Kind.STARTED && request.getStatus() == ServiceRequestStatus.ENGINEER_ASSIGNED) {
            entries.flush();
            requests.transition(requestId, ServiceRequestAction.START_WORK, null, null);
        }
        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role().name())
                .action(AuditAction.JOB_LOG_ADDED)
                .entity("ServiceRequest", requestId)
                .scope(request.getOrganizationId(), request.getVesselId())
                .after(AuditJson.of("requestNumber", request.getRequestNumber(), "kind", what.name(),
                        "occurredAt", when, "note", text, "documentId", documentId))
                .build());

        return view(requestId).entries().stream()
                .filter(v -> v.id().equals(entry.getId())).findFirst().orElseThrow();
    }

    /** The workflow's own entries: work started, and the report submitted. Same transaction as the step. */
    @EventListener
    public void onTransition(ServiceRequestEvents.Transitioned t) {
        JobLogEntry.Kind kind = t.action() == ServiceRequestAction.START_WORK ? JobLogEntry.Kind.STARTED
                : t.action() == ServiceRequestAction.SUBMIT_COMPLETION ? JobLogEntry.Kind.REPORTED
                : null;
        if (kind == null || t.actorUserId() == null) return;
        if (kind == JobLogEntry.Kind.STARTED && entries.existsByServiceRequestIdAndKind(t.serviceRequestId(), kind)) {
            return;
        }
        entries.save(new JobLogEntry(t.serviceRequestId(), t.vesselId(), t.actorUserId(), kind,
                t.occurredAt() == null ? Instant.now() : t.occurredAt(),
                kind == JobLogEntry.Kind.STARTED ? "Work started." : "Completion report submitted to the Coordinator.",
                null, true));
    }

    private static boolean canWrite(AccessScope scope, ServiceRequest request) {
        return scope.role() == Role.SERVICE_ENGINEER
                && Objects.equals(request.getAssignedEngineerUserId(), scope.userId())
                && OPEN_FOR_LOG.contains(request.getStatus());
    }

    private static String label(JobLogEntry.Kind kind) {
        return switch (kind) {
            case ARRIVED -> "Arrived on board";
            case STARTED -> "Work started";
            case UPDATE -> "Progress update";
            case WAITING -> "Waiting";
            case RESUMED -> "Work resumed";
            case FINISHED -> "Work finished";
            case LEFT -> "Left the vessel";
            case REPORTED -> "Report submitted";
        };
    }

    record JobLog(boolean canWrite, List<EntryView> entries) {}

    record EntryView(Long id, String kind, Instant occurredAt, String note, String authorName, String authorRole,
                     boolean automatic, Instant recordedAt, PhotoView photo) {}

    record PhotoView(Long documentId, String fileName, String contentType, long sizeBytes, boolean image,
                     boolean video) {}
}
