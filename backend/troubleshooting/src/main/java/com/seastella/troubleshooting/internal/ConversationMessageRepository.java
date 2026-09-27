package com.seastella.troubleshooting.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** Unread for one reader: what arrived after their read mark, less what they wrote. */
    @Query("select count(m) from ConversationMessage m where m.conversationId = :conversationId "
            + "and m.id > :afterId and (m.senderUserId is null or m.senderUserId <> :readerId)")
    long countUnreadFor(@Param("conversationId") Long conversationId, @Param("afterId") Long afterId,
                        @Param("readerId") Long readerId);

    /** Search within one thread (CHT-09). */
    List<ConversationMessage> findByConversationIdAndBodyContainingIgnoreCaseOrderByIdAsc(Long conversationId, String text);
}
