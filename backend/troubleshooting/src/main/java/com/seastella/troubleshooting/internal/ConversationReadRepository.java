package com.seastella.troubleshooting.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationReadRepository extends JpaRepository<ConversationRead, Long> {

    Optional<ConversationRead> findByConversationIdAndUserId(Long conversationId, Long userId);

    List<ConversationRead> findByConversationId(Long conversationId);
}
