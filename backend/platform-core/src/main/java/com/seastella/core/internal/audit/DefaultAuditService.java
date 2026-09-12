package com.seastella.core.internal.audit;

import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.security.ActorProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
class DefaultAuditService implements AuditService {

    private final AuditEntryRepository repository;
    private final ActorProvider actorProvider;

    DefaultAuditService(AuditEntryRepository repository, ActorProvider actorProvider) {
        this.repository = repository;
        this.actorProvider = actorProvider;
    }

    /**
     * MANDATORY propagation, not REQUIRED: an audit write outside a business
     * transaction is a bug we want surfaced at development time rather than a
     * row that silently survives a rolled-back change.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        repository.save(entry);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, Long entityId, String before, String after) {
        AuditEntry.Builder b = AuditEntry.builder()
                .action(action)
                .entity(entityType, entityId)
                .before(before)
                .after(after);

        actorProvider.currentActor().ifPresent(a ->
                b.actor(a.userId(), a.role()).scope(a.organizationId(), null));

        currentRequest().ifPresent(r ->
                b.request(clientIp(r), truncate(r.getHeader("User-Agent"))));

        repository.save(b.build());
    }

    private static java.util.Optional<HttpServletRequest> currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra) {
            return java.util.Optional.of(sra.getRequest());
        }
        return java.util.Optional.empty();
    }

    /** Honours X-Forwarded-For, since the app sits behind a reverse proxy. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return truncate45(comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim());
        }
        return truncate45(request.getRemoteAddr());
    }

    private static String truncate45(String s) {
        if (s == null) return null;
        return s.length() <= 45 ? s : s.substring(0, 45);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 255 ? s : s.substring(0, 255);
    }
}
