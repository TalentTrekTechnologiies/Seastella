package com.seastella.troubleshooting.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {

    List<ConversationMessage> findByConversationIdAndIdGreaterThanOrderByIdAsc(Long conversationId, Long afterId);

    Optional<ConversationMessage> findByConversationIdAndClientMsgId(Long conversationId, String clientMsgId);

    List<ConversationMessage> findByConversationIdOrderByIdAsc(Long conversationId);

    /** Newest message first, to answer "how far is there to read" in one row. */
    Optional<ConversationMessage> findFirstByConversationIdOrderByIdDesc(Long conversationId);

    /** Unread is counted, not derived from a client's idea of what it has seen (CHT-07). */
    long countByConversationIdAndIdGreaterThan(Long conversationId, Long afterId);

    /** Search within one thread (CHT-09). */
    List<ConversationMessage> findByConversationIdAndBodyContainingIgnoreCaseOrderByIdAsc(Long conversationId, String text);
}
