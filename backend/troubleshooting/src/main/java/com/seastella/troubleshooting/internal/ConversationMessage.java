package com.seastella.troubleshooting.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One line of the transcript. Append-only.
 *
 * <p>Three kinds share the thread (CHT-04): what the assistant asked, what a
 * person said, and what the platform recorded happening. A line may carry one
 * attachment, which is a document like any other - the message points at it.
 */
@Entity
@Table(name = "conversation_message")
public class ConversationMessage extends BaseEntity {

    static final String USER = "USER";
    static final String SYSTEM = "SYSTEM";
    static final String ASSISTANT = "ASSISTANT";

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "sender_kind", nullable = false, length = 16)
    private String senderKind;

    @Column(name = "sender_user_id")
    private Long senderUserId;

    @Column(name = "sender_role", length = 32)
    private String senderRole;

    @Column(name = "body", nullable = false, length = 2000)
    private String body;

    @Column(name = "client_msg_id", length = 64)
    private String clientMsgId;

    @Column(name = "document_id")
    private Long documentId;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected ConversationMessage() {
    }

    static ConversationMessage fromUser(Long conversationId, Long userId, String role, String body,
                                        String clientMsgId, Instant at) {
        ConversationMessage m = new ConversationMessage();
        m.conversationId = conversationId;
        m.senderKind = USER;
        m.senderUserId = userId;
        m.senderRole = role;
        m.body = body;
        m.clientMsgId = clientMsgId;
        m.sentAt = at;
        return m;
    }

    static ConversationMessage system(Long conversationId, String body, Instant at) {
        return platformLine(conversationId, SYSTEM, body, at);
    }

    /** A question the guided checks asked, in the same thread as the answers. */
    static ConversationMessage assistant(Long conversationId, String body, Instant at) {
        return platformLine(conversationId, ASSISTANT, body, at);
    }

    private static ConversationMessage platformLine(Long conversationId, String kind, String body, Instant at) {
        ConversationMessage m = new ConversationMessage();
        m.conversationId = conversationId;
        m.senderKind = kind;
        m.body = body.length() > 2000 ? body.substring(0, 1999) + "…" : body;
        m.sentAt = at;
        return m;
    }

    void attach(Long documentId) {
        this.documentId = documentId;
    }

    Long getConversationId() { return conversationId; }
    String getSenderKind() { return senderKind; }
    Long getSenderUserId() { return senderUserId; }
    String getSenderRole() { return senderRole; }
    String getBody() { return body; }
    String getClientMsgId() { return clientMsgId; }
    Long getDocumentId() { return documentId; }
    Instant getSentAt() { return sentAt; }
}
