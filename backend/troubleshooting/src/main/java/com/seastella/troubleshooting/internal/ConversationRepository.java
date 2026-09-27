package com.seastella.troubleshooting.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByServiceRequestId(Long serviceRequestId);

    List<Conversation> findByVesselIdIn(Collection<Long> vesselIds);
}
