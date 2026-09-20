package com.seastella.troubleshooting.internal;

import com.seastella.core.api.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * How far one person has read one conversation (CHT-07).
 *
 * <p>Kept as a message id rather than a timestamp, so a read marker can never
 * disagree with the order of the transcript, and never moves backwards: a
 * reader scrolling up does not un-read what they have already seen.
 */
@Entity
@Table(name = "conversation_read")
public class ConversationRead extends BaseEntity {

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "last_read_message_id", nullable = false)
    private Long lastReadMessageId;

    @Column(name = "read_at", nullable = false)
    private Instant readAt;

    protected ConversationRead() {
    }

    ConversationRead(Long conversationId, Long userId, Long lastReadMessageId, Instant readAt) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.lastReadMessageId = lastReadMessageId;
        this.readAt = readAt;
    }

    Long getUserId() { return userId; }
    Long getLastReadMessageId() { return lastReadMessageId; }
    Instant getReadAt() { return readAt; }

    /** @return true when this moved the marker forward. */
    boolean readTo(Long messageId, Instant at) {
        if (messageId == null || messageId <= lastReadMessageId) return false;
        this.lastReadMessageId = messageId;
        this.readAt = at;
        return true;
    }
}
