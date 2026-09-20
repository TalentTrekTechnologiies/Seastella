package com.seastella.activityfeed.internal;

import com.seastella.core.api.audit.AuditEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The activity feed, pushed rather than polled (FEE-04).
 *
 * <p>An open feed used to ask the server every ten seconds whether anything
 * had happened; almost every ask returned the same page. Now the server says
 * when something has, and the browser fetches only what is newer than the last
 * id it holds.
 *
 * <p>What travels down the stream is the audit entry's id, not the entry. The
 * feed already knows how to read an entry and how to say it in words, and one
 * shape in one place cannot disagree with itself. A client that misses a push -
 * a laptop that slept, a proxy that dropped the connection - loses nothing: it
 * reconnects and asks for everything after the id it has.
 *
 * <p>Announcements are sent after commit, so nothing is announced that a
 * rollback then takes away.
 */
@Component
class ActivityStream {

    private static final Logger log = LoggerFactory.getLogger(ActivityStream.class);

    /** Long, because the stream is idle most of the time; the heartbeat keeps it open. */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> {
            subscribers.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> subscribers.remove(emitter));
        try {
            // An immediate frame so the browser's connection is confirmed open
            // rather than sitting in "connecting" until something happens.
            emitter.send(SseEmitter.event().name("open").data(Map.of("ok", true)));
        } catch (IOException e) {
            subscribers.remove(emitter);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAuditRecorded(AuditEvents.Recorded event) {
        send("activity", Map.of("id", event.entryId(), "action", event.action()));
    }

    /**
     * Proxies close an idle connection, often after a minute. A comment frame
     * every twenty seconds keeps it alive and costs nothing.
     */
    @Scheduled(fixedDelay = 20_000)
    void heartbeat() {
        if (subscribers.isEmpty()) return;
        send("heartbeat", Map.of("at", java.time.Instant.now().toString()));
    }

    private void send(String name, Object payload) {
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().name(name).data(payload));
            } catch (IOException | IllegalStateException e) {
                // The client went away. Drop it quietly: a closed browser tab is
                // not an error worth a stack trace in the log.
                subscribers.remove(emitter);
                log.debug("activity stream subscriber dropped: {}", e.toString());
            }
        }
    }

    int subscriberCount() {
        return subscribers.size();
    }
}
