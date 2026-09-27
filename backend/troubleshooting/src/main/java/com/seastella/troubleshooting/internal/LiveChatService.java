package com.seastella.troubleshooting.internal;

import com.seastella.core.api.error.ForbiddenException;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
import com.seastella.fleet.api.RequestAttachments;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeKind;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.identity.api.UserDirectory;
import com.seastella.servicerequest.api.ServiceRequestAction;
import com.seastella.servicerequest.api.ServiceRequestCommands;
import com.seastella.servicerequest.api.ServiceRequestEvents;
import com.seastella.servicerequest.api.ServiceRequestMetrics;
import com.seastella.servicerequest.api.ServiceRequestMetrics.RequestSummary;
import com.seastella.servicerequest.api.ServiceRequestStatus;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The thread on a service request (SoW s6.1, SRS s21).
 *
 * <p>One thread per request, shared with the guided checks (CHT-04): the
 * assistant's questions, the Captain's answers, the conversation between the
 * people on the request and the platform's own notes are all the same
 * transcript, in order. The status says where it is - ASSISTANT while the
 * checks run, OPEN for the rest of the request's life, LIVE while a Captain has
 * a live agent engaged (s6.1 step 3), and CLOSED once the request is finished.
 *
 * <p>Everyone working the request writes in it - the Captain, the Ship Manager
 * and Technical Head, the Coordinators serving the vessel and the engineer on
 * the job - so whoever has to answer can. The Platform Admin reads it all but
 * does not take part. Scope decides whose request it is: a person who cannot
 * see the request cannot see or write in its thread.
 *
 * <p>Human-to-human only (s15). The client polls; the transcript, not the
 * transport, is the requirement.
 */
@Service
class LiveChatService {

    private static final int MAX_BODY = 2000;
    private static final int PREVIEW = 140;
    private static final int INBOX_SIZE = 50;

    /** Who takes part in a request's thread. The Platform Admin oversees it. */
    static final Set<Role> WRITERS = EnumSet.of(Role.CAPTAIN, Role.SHIP_MANAGER, Role.TECHNICAL_HEAD,
            Role.SERVICE_COORDINATOR, Role.SERVICE_ENGINEER);

    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final ConversationReadRepository reads;
    private final ConversationThread thread;
    private final ServiceRequestCommands requests;
    private final ServiceRequestMetrics metrics;
    private final RequestAttachments attachments;
    private final ScopeResolver scopes;
    private final UserDirectory users;

    LiveChatService(ConversationRepository conversations, ConversationMessageRepository messages,
                    ConversationReadRepository reads, ConversationThread thread,
                    ServiceRequestCommands requests, ServiceRequestMetrics metrics,
                    RequestAttachments attachments, ScopeResolver scopes, UserDirectory users) {
        this.conversations = conversations;
        this.messages = messages;
        this.reads = reads;
        this.thread = thread;
        this.requests = requests;
        this.metrics = metrics;
        this.attachments = attachments;
        this.scopes = scopes;
        this.users = users;
    }

    // ------------------------------------------------------- opening, closing

    /** Same transaction as the transition: the chat opens or closes with it. */
    @EventListener
    public void onTransition(ServiceRequestEvents.Transitioned t) {
        Instant now = t.occurredAt() == null ? Instant.now() : t.occurredAt();
        String actor = users.find(t.actorUserId()).map(UserDirectory.UserRef::fullName).orElse("Someone");

        if (t.action() == ServiceRequestAction.ESCALATE_TO_LIVE_AGENT) {
            // The thread usually exists already: the guided checks opened it.
            Conversation c = thread.open(t.serviceRequestId(), t.vesselId(), Conversation.LIVE, now);
            thread.escalate(c, now);
            thread.system(c, actor + " escalated " + t.requestNumber() + " to a live agent.", now);
            return;
        }

        // A finished request finishes its thread; the transcript stays.
        if (t.toStatus() != null && t.toStatus().isTerminal()) {
            conversations.findByServiceRequestId(t.serviceRequestId())
                    .filter(c -> !c.isClosed())
                    .ifPresent(c -> {
                        thread.close(c, now);
                        thread.system(c, "Conversation closed: " + actor + " — " + t.action().label().toLowerCase()
                                + ". The history stays on the request.", now);
                    });
            return;
        }

        // Moving on from the live agent ends that session, not the thread.
        if (t.fromStatus() == ServiceRequestStatus.LIVE_AGENT_ESCALATED) {
            conversations.findByServiceRequestId(t.serviceRequestId())
                    .filter(Conversation::isLive)
                    .ifPresent(c -> {
                        thread.endLive(c);
                        thread.system(c, "Live agent session ended: " + actor + " — "
                                + t.action().label().toLowerCase() + ".", now);
                    });
        }
    }

    // ------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    ChatView view(Long requestId, Long afterId) {
        requests.placementInScope(requestId);                          // 404 outside scope
        RequestSummary request = metrics.summary(requestId)
                .orElseThrow(() -> NotFoundException.ofResource("ServiceRequest", requestId));
        AccessScope scope = scopes.currentScope();

        Conversation c = conversations.findByServiceRequestId(requestId).orElse(null);
        if (c == null) {
            // Nothing written yet. An open request's thread is there to start;
            // a finished one that never had a word said has nothing to show.
            if (request.status().isTerminal()) {
                return new ChatView("NONE", false, null, null, null, 0, null, null, List.of());
            }
            boolean live = request.status() == ServiceRequestStatus.LIVE_AGENT_ESCALATED;
            return new ChatView(live ? Conversation.LIVE : Conversation.OPEN, WRITERS.contains(scope.role()),
                    null, null, null, 0, null, null, List.of());
        }

        List<ConversationMessage> found = messages.findByConversationIdAndIdGreaterThanOrderByIdAsc(
                c.getId(), afterId == null ? 0L : afterId);

        Long lastRead = reads.findByConversationIdAndUserId(c.getId(), scope.userId())
                .map(ConversationRead::getLastReadMessageId).orElse(0L);
        long unread = messages.countUnreadFor(c.getId(), lastRead, scope.userId());
        Long readByOthers = reads.findByConversationId(c.getId()).stream()
                .filter(r -> !Objects.equals(r.getUserId(), scope.userId()))
                .map(ConversationRead::getLastReadMessageId)
                .max(Long::compareTo).orElse(null);

        return new ChatView(c.getStatus(), canSend(scope, c, request), c.getOpenedAt(), c.getEscalatedAt(),
                c.getClosedAt(), unread, lastRead == 0L ? null : lastRead, readByOthers, views(found, scope));
    }

    /** Find a word in this thread (CHT-09). Search is scoped to one request. */
    @Transactional(readOnly = true)
    List<MessageView> search(Long requestId, String query) {
        requests.placementInScope(requestId);
        String q = query == null ? "" : query.strip();
        if (q.length() < 2) throw new ValidationException("Type at least two characters to search.");
        AccessScope scope = scopes.currentScope();
        Conversation c = conversations.findByServiceRequestId(requestId).orElse(null);
        if (c == null) return List.of();
        return views(messages.findByConversationIdAndBodyContainingIgnoreCaseOrderByIdAsc(c.getId(), q), scope);
    }

    // ------------------------------------------------------------------ write

    @Transactional
    MessageView send(Long requestId, String body, String clientMsgId) {
        AccessScope scope = scopes.currentScope();
        Conversation c = writable(requestId, scope);

        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) throw new ValidationException("Type a message.");
        if (text.length() > MAX_BODY) throw new ValidationException("Keep a message under " + MAX_BODY + " characters.");
        String key = clientKey(clientMsgId);

        // A retried send returns the message already stored rather than a duplicate (CHT-13).
        if (key != null) {
            ConversationMessage existing = messages.findByConversationIdAndClientMsgId(c.getId(), key).orElse(null);
            if (existing != null) return toView(existing, scope);
        }
        return toView(thread.fromUser(c, scope.userId(), scope.role().name(), text, key, Instant.now()), scope);
    }

    /**
     * A photograph, a video or a document, sent in the chat (CHT-08). The file
     * is stored as a document on the request - same checks, same scope, same
     * audit entry - and the message points at it.
     */
    @Transactional
    MessageView sendAttachment(Long requestId, String fileName, byte[] content, String caption, String clientMsgId) {
        AccessScope scope = scopes.currentScope();
        Conversation c = writable(requestId, scope);
        String key = clientKey(clientMsgId);
        if (key != null) {
            ConversationMessage existing = messages.findByConversationIdAndClientMsgId(c.getId(), key).orElse(null);
            if (existing != null) return toView(existing, scope);
        }

        String text = caption == null ? "" : caption.strip();
        if (text.length() > MAX_BODY) throw new ValidationException("Keep a caption under " + MAX_BODY + " characters.");

        RequestAttachments.AttachmentRef stored = attachments.attach(
                c.getVesselId(), requestId, fileName, content, text.isEmpty() ? null : text);
        ConversationMessage message = thread.fromUser(c, scope.userId(), scope.role().name(),
                text.isEmpty() ? stored.fileName() : text, key, Instant.now());
        message.attach(stored.documentId());
        messages.save(message);
        return toView(message, scope);
    }

    /**
     * Marks the thread read as far as a message (CHT-07). Idempotent, and never
     * moves backwards: re-reading an old line does not un-read the newer ones.
     */
    @Transactional
    ReadView markRead(Long requestId, Long lastMessageId) {
        requests.placementInScope(requestId);
        AccessScope scope = scopes.currentScope();
        Conversation c = conversations.findByServiceRequestId(requestId).orElse(null);
        if (c == null) return new ReadView(null, 0);

        Long upTo = lastMessageId != null ? lastMessageId
                : messages.findFirstByConversationIdOrderByIdDesc(c.getId()).map(ConversationMessage::getId).orElse(null);
        if (upTo == null) return new ReadView(null, 0);
        // A message id from another thread must not mark this one read.
        ConversationMessage target = messages.findById(upTo).orElse(null);
        if (target == null || !Objects.equals(target.getConversationId(), c.getId())) {
            throw new ValidationException("That message is not in this conversation.");
        }

        Instant now = Instant.now();
        ConversationRead state = reads.findByConversationIdAndUserId(c.getId(), scope.userId()).orElse(null);
        if (state == null) {
            state = reads.save(new ConversationRead(c.getId(), scope.userId(), upTo, now));
        } else if (state.readTo(upTo, now)) {
            reads.save(state);
        }
        return new ReadView(state.getLastReadMessageId(),
                messages.countUnreadFor(c.getId(), state.getLastReadMessageId(), scope.userId()));
    }

    // ------------------------------------------------------------------ inbox

    /**
     * The caller's threads, newest message first, with what is unread in each:
     * what the chat button shows. Only threads with something in them, on
     * requests the caller can see.
     */
    @Transactional(readOnly = true)
    Inbox inbox() {
        AccessScope scope = scopes.currentScope();
        List<Conversation> found;
        if (scope.isPlatformWide()) {
            found = conversations.findAll();
        } else if (scope.vesselIds().isEmpty()) {
            found = List.of();
        } else {
            found = conversations.findByVesselIdIn(scope.vesselIds());
        }
        if (scope.kind() == ScopeKind.JOB_SET) {
            // An engineer's vessels are those of their jobs; only the jobs themselves are theirs.
            found = found.stream().filter(c -> scope.assignedJobIds().contains(c.getServiceRequestId())).toList();
        }

        List<ThreadSummary> threads = found.stream()
                .map(c -> summarise(c, scope))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing((ThreadSummary t) -> t.lastMessage().sentAt()).reversed())
                .toList();
        long unread = threads.stream().mapToLong(ThreadSummary::unreadCount).sum();
        return new Inbox(unread, threads.stream().limit(INBOX_SIZE).toList());
    }

    private ThreadSummary summarise(Conversation c, AccessScope scope) {
        ConversationMessage last = messages.findFirstByConversationIdOrderByIdDesc(c.getId()).orElse(null);
        if (last == null) return null;
        RequestSummary request = metrics.summary(c.getServiceRequestId()).orElse(null);
        if (request == null) return null;

        Long lastRead = reads.findByConversationIdAndUserId(c.getId(), scope.userId())
                .map(ConversationRead::getLastReadMessageId).orElse(0L);
        String sender = switch (last.getSenderKind()) {
            case "ASSISTANT" -> "Guided checks";
            case "SYSTEM" -> null;
            default -> last.getSenderUserId() == null ? null
                    : users.find(last.getSenderUserId()).map(UserDirectory.UserRef::fullName).orElse(null);
        };
        String body = last.getBody() == null ? "" : last.getBody().strip();
        String preview = body.length() > PREVIEW ? body.substring(0, PREVIEW - 1) + "…" : body;

        return new ThreadSummary(request.id(), request.requestNumber(), request.vesselName(), request.spareName(),
                request.title(), request.statusLabel(), c.getStatus(), canSend(scope, c, request),
                messages.countUnreadFor(c.getId(), lastRead, scope.userId()),
                new LastMessage(last.getSenderKind(), sender, last.getSenderRole(),
                        Objects.equals(last.getSenderUserId(), scope.userId()), preview,
                        last.getDocumentId() != null, last.getSentAt()));
    }

    // -------------------------------------------------------------- internals

    /** The thread, checked as far as "this caller may write in it right now". */
    private Conversation writable(Long requestId, AccessScope scope) {
        requests.placementInScope(requestId);
        RequestSummary request = metrics.summary(requestId)
                .orElseThrow(() -> NotFoundException.ofResource("ServiceRequest", requestId));

        if (!WRITERS.contains(scope.role())) {
            throw ForbiddenException.ofAction("write in this request's conversation");
        }
        if (request.status().isTerminal()) {
            throw new WorkflowException("This request is finished. Its conversation stays on the request to read.");
        }
        Conversation c = conversations.findByServiceRequestId(requestId).orElse(null);
        if (c == null) {
            boolean live = request.status() == ServiceRequestStatus.LIVE_AGENT_ESCALATED;
            c = thread.open(requestId, request.vesselId(), live ? Conversation.LIVE : Conversation.OPEN, Instant.now());
        }
        if (!canSend(scope, c, request)) {
            throw new WorkflowException("This conversation is closed. The history stays on the request.");
        }
        return c;
    }

    private static String clientKey(String clientMsgId) {
        String key = clientMsgId == null || clientMsgId.isBlank() ? null : clientMsgId.strip();
        if (key != null && key.length() > 64) throw new ValidationException("Invalid message id.");
        return key;
    }

    private static boolean canSend(AccessScope scope, Conversation c, RequestSummary request) {
        return WRITERS.contains(scope.role()) && !c.isClosed() && !request.status().isTerminal();
    }

    private List<MessageView> views(List<ConversationMessage> found, AccessScope scope) {
        Map<Long, UserDirectory.UserRef> people = users.findAll(
                found.stream().map(ConversationMessage::getSenderUserId).filter(Objects::nonNull).toList());
        Map<Long, RequestAttachments.AttachmentRef> files = attachments.describe(
                found.stream().map(ConversationMessage::getDocumentId).filter(Objects::nonNull).toList());

        return found.stream().map(m -> {
            UserDirectory.UserRef who = m.getSenderUserId() == null ? null : people.get(m.getSenderUserId());
            return new MessageView(m.getId(), m.getSenderKind(), who == null ? null : who.fullName(),
                    m.getSenderRole(), m.getBody(), m.getSentAt(),
                    Objects.equals(m.getSenderUserId(), scope.userId()),
                    attachment(m.getDocumentId() == null ? null : files.get(m.getDocumentId())));
        }).toList();
    }

    private MessageView toView(ConversationMessage m, AccessScope scope) {
        String name = m.getSenderUserId() == null ? null
                : users.find(m.getSenderUserId()).map(UserDirectory.UserRef::fullName).orElse(null);
        RequestAttachments.AttachmentRef file = m.getDocumentId() == null ? null
                : attachments.describe(List.of(m.getDocumentId())).get(m.getDocumentId());
        return new MessageView(m.getId(), m.getSenderKind(), name, m.getSenderRole(), m.getBody(), m.getSentAt(),
                Objects.equals(m.getSenderUserId(), scope.userId()), attachment(file));
    }

    private static AttachmentView attachment(RequestAttachments.AttachmentRef ref) {
        return ref == null ? null
                : new AttachmentView(ref.documentId(), ref.fileName(), ref.contentType(), ref.sizeBytes(),
                ref.isImage(), ref.isVideo());
    }

    record ChatView(String status, boolean canSend, Instant openedAt, Instant escalatedAt, Instant closedAt,
                    long unreadCount, Long lastReadMessageId, Long readByOthersMessageId,
                    List<MessageView> messages) {}

    record MessageView(Long id, String kind, String senderName, String senderRole, String body, Instant sentAt,
                       boolean mine, AttachmentView attachment) {}

    record AttachmentView(Long documentId, String fileName, String contentType, long sizeBytes,
                          boolean image, boolean video) {}

    record ReadView(Long lastReadMessageId, long unreadCount) {}

    record Inbox(long unreadCount, List<ThreadSummary> threads) {}

    record ThreadSummary(Long requestId, String requestNumber, String vesselName, String spareName, String title,
                         String requestStatus, String status, boolean canSend, long unreadCount,
                         LastMessage lastMessage) {}

    record LastMessage(String kind, String senderName, String senderRole, boolean mine, String preview,
                       boolean attachment, Instant sentAt) {}
}
