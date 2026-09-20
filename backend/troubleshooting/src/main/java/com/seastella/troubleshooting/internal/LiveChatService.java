package com.seastella.troubleshooting.internal;

import com.seastella.core.api.error.ForbiddenException;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
import com.seastella.fleet.api.RequestAttachments;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Live Agent Chat: Captain and Service Coordinator, on the request (SoW s6.1).
 *
 * <p>One thread per request, shared with the guided checks (CHT-04): the
 * assistant's questions, the Captain's answers, the live conversation and the
 * platform's own notes are all the same transcript, in order. The status says
 * where it is - ASSISTANT while the checks run, LIVE once a Captain escalates,
 * CLOSED when the request moves on (CHT-10).
 *
 * <p>Only the Captain on the vessel and the Coordinators serving it write;
 * anyone who can see the request reads it, because s6.2 attaches the log for
 * the Ship Manager's approval and the engineer's context.
 *
 * <p>Human-to-human only (s15). The client polls; the transcript, not the
 * transport, is the requirement.
 */
@Service
class LiveChatService {

    private static final int MAX_BODY = 2000;

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

        if (t.fromStatus() == ServiceRequestStatus.LIVE_AGENT_ESCALATED) {
            conversations.findByServiceRequestId(t.serviceRequestId())
                    .filter(Conversation::isLive)
                    .ifPresent(c -> {
                        thread.close(c, now);
                        thread.system(c, "Chat closed: " + actor + " — " + t.action().label().toLowerCase() + ".", now);
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
            // Nothing written yet: no checks run, no escalation. The Captain and
            // the Coordinator still see whether a live chat is open to them.
            boolean live = request.status() == ServiceRequestStatus.LIVE_AGENT_ESCALATED;
            boolean writer = scope.role() == Role.CAPTAIN || scope.role() == Role.SERVICE_COORDINATOR;
            return new ChatView(live ? Conversation.LIVE : "NONE", live && writer,
                    null, null, null, 0, null, null, List.of());
        }

        List<ConversationMessage> found = messages.findByConversationIdAndIdGreaterThanOrderByIdAsc(
                c.getId(), afterId == null ? 0L : afterId);

        Long lastRead = reads.findByConversationIdAndUserId(c.getId(), scope.userId())
                .map(ConversationRead::getLastReadMessageId).orElse(0L);
        long unread = messages.countByConversationIdAndIdGreaterThan(c.getId(), lastRead);
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
                messages.countByConversationIdAndIdGreaterThan(c.getId(), state.getLastReadMessageId()));
    }

    // -------------------------------------------------------------- internals

    /** The thread, checked as far as "this caller may write in it right now". */
    private Conversation writable(Long requestId, AccessScope scope) {
        requests.placementInScope(requestId);
        RequestSummary request = metrics.summary(requestId)
                .orElseThrow(() -> NotFoundException.ofResource("ServiceRequest", requestId));

        if (scope.role() != Role.CAPTAIN && scope.role() != Role.SERVICE_COORDINATOR) {
            throw ForbiddenException.ofAction("write in the live chat");
        }
        Conversation c = conversations.findByServiceRequestId(requestId).orElse(null);
        if (c == null) {
            if (request.status() != ServiceRequestStatus.LIVE_AGENT_ESCALATED) {
                throw new WorkflowException("The Captain has not escalated this request to a live agent.");
            }
            c = thread.open(requestId, request.vesselId(), Conversation.LIVE, Instant.now());
        }
        if (!canSend(scope, c, request)) {
            throw new WorkflowException(c.isClosed()
                    ? "This chat is closed. The transcript stays on the request."
                    : "The Captain has not escalated this request to a live agent.");
        }
        return c;
    }

    private static String clientKey(String clientMsgId) {
        String key = clientMsgId == null || clientMsgId.isBlank() ? null : clientMsgId.strip();
        if (key != null && key.length() > 64) throw new ValidationException("Invalid message id.");
        return key;
    }

    private static boolean canSend(AccessScope scope, Conversation c, RequestSummary request) {
        return (scope.role() == Role.CAPTAIN || scope.role() == Role.SERVICE_COORDINATOR)
                && c.isLive()
                && request.status() == ServiceRequestStatus.LIVE_AGENT_ESCALATED;
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
}
