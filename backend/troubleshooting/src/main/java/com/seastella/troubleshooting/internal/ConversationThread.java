package com.seastella.troubleshooting.internal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The one thread on a request (CHT-04).
 *
 * <p>Both halves of SoW §6.1 write here: the guided checks as they ask and are
 * answered, and the live chat once a Captain escalates. Keeping the writes in
 * one place is what makes "every question, check and message ... is logged
 * against the request" true of a single transcript rather than of two logs that
 * have to be read side by side.
 *
 * <p>Every method joins the caller's transaction: a line of transcript is part
 * of the thing it describes, not a separate event that might or might not land.
 */
@Service
class ConversationThread {

    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;

    ConversationThread(ConversationRepository conversations, ConversationMessageRepository messages) {
        this.conversations = conversations;
        this.messages = messages;
    }

    /**
     * The thread for a request, opened in {@code status} if this is the first
     * line written to it. An existing thread keeps the status it has: opening
     * does not re-open a closed conversation or demote a live one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Conversation open(Long requestId, Long vesselId, String status, Instant at) {
        return conversations.findByServiceRequestId(requestId)
                .orElseGet(() -> conversations.save(new Conversation(requestId, vesselId, status, at)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void escalate(Conversation conversation, Instant at) {
        if (conversation.isClosed()) return;
        conversation.escalate(at);
        conversations.save(conversation);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void close(Conversation conversation, Instant at) {
        conversation.close(at);
        conversations.save(conversation);
    }

    /** A question the guided checks put to the Captain. */
    @Transactional(propagation = Propagation.MANDATORY)
    void assistant(Conversation conversation, String body, Instant at) {
        messages.save(ConversationMessage.assistant(conversation.getId(), body, at));
    }

    /** What the platform recorded happening: started, escalated, closed. */
    @Transactional(propagation = Propagation.MANDATORY)
    void system(Conversation conversation, String body, Instant at) {
        messages.save(ConversationMessage.system(conversation.getId(), body, at));
    }

    /** A person's line. Used for chat sends and for answers to the guided checks. */
    @Transactional(propagation = Propagation.MANDATORY)
    ConversationMessage fromUser(Conversation conversation, Long userId, String role, String body,
                                 String clientMsgId, Instant at) {
        return messages.save(ConversationMessage.fromUser(conversation.getId(), userId, role, body, clientMsgId, at));
    }
}
