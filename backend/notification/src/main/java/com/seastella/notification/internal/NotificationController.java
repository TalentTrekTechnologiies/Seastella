package com.seastella.notification.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.ScopeResolver;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The in-app notification centre (NOT-12).
 *
 * <p>A user's inbox is exactly their own alerts: every read and write here is
 * keyed on the authenticated user, so another person's notification id answers
 * 404 like any other out-of-scope resource.
 */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private final NotificationRepository notifications;
    private final NotificationDeliveryRepository deliveries;
    private final NotificationRuleRepository rules;
    private final ScopeResolver scopeResolver;
    private final AuditService audit;

    NotificationController(NotificationRepository notifications, NotificationDeliveryRepository deliveries,
                           NotificationRuleRepository rules, ScopeResolver scopeResolver, AuditService audit) {
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.rules = rules;
        this.scopeResolver = scopeResolver;
        this.audit = audit;
    }

    @GetMapping
    @Transactional(readOnly = true)
    ResponseEntity<Inbox> inbox(@RequestParam(defaultValue = "30") int limit) {
        Long me = scopeResolver.currentScope().userId();
        int size = Math.min(Math.max(limit, 1), 100);
        List<Item> items = notifications
                .findByRecipientUserIdAndInAppTrueOrderByCreatedAtDescIdDesc(me, PageRequest.of(0, size))
                .stream().map(NotificationController::toItem).toList();
        return ResponseEntity.ok(new Inbox(notifications.countByRecipientUserIdAndInAppTrueAndReadAtIsNull(me), items));
    }

    @GetMapping("/unread-count")
    @Transactional(readOnly = true)
    ResponseEntity<UnreadCount> unreadCount() {
        Long me = scopeResolver.currentScope().userId();
        return ResponseEntity.ok(new UnreadCount(notifications.countByRecipientUserIdAndInAppTrueAndReadAtIsNull(me)));
    }

    @PostMapping("/{id}/read")
    @Transactional
    ResponseEntity<UnreadCount> markRead(@PathVariable Long id) {
        Long me = scopeResolver.currentScope().userId();
        Notification n = notifications.findByIdAndRecipientUserId(id, me)
                .orElseThrow(() -> NotFoundException.ofResource("Notification", id));
        n.markRead(Instant.now());
        notifications.save(n);
        return ResponseEntity.ok(new UnreadCount(notifications.countByRecipientUserIdAndInAppTrueAndReadAtIsNull(me)));
    }

    @PostMapping("/read-all")
    @Transactional
    ResponseEntity<UnreadCount> markAllRead() {
        Long me = scopeResolver.currentScope().userId();
        notifications.markAllRead(me, Instant.now());
        return ResponseEntity.ok(new UnreadCount(0));
    }

    /** Email delivery log, newest first (SoW s8.5 system status). */
    @GetMapping("/deliveries")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Transactional(readOnly = true)
    ResponseEntity<List<DeliveryView>> deliveryLog(@RequestParam(defaultValue = "100") int limit) {
        List<NotificationDelivery> recent = deliveries.findAllByOrderByIdDesc(
                PageRequest.of(0, Math.min(Math.max(limit, 1), 500)));
        Map<Long, Notification> byId = notifications.findAllById(
                        recent.stream().map(NotificationDelivery::getNotificationId).toList())
                .stream().collect(Collectors.toMap(Notification::getId, Function.identity()));

        return ResponseEntity.ok(recent.stream().map(d -> {
            Notification n = byId.get(d.getNotificationId());
            return new DeliveryView(d.getId(), d.getChannel(), d.getAddress(), d.getStatus(), d.getAttempts(),
                    d.getLastError(), d.getSentAt(), d.getCreatedAt(),
                    n == null ? null : n.getEventType(), n == null ? null : n.getTitle());
        }).toList());
    }

    /** Who hears about what (SoW s11), as configured. */
    @GetMapping("/rules")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Transactional(readOnly = true)
    ResponseEntity<List<RuleView>> rules() {
        return ResponseEntity.ok(rules.findAllByOrderByEventTypeAscRecipientRoleAsc().stream()
                .map(NotificationController::toRule).toList());
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Transactional
    ResponseEntity<RuleView> configureRule(@PathVariable Long id, @RequestBody RuleUpdate body) {
        if (body == null || body.inApp() == null || body.email() == null || body.active() == null) {
            throw new ValidationException("Say whether the rule is active, and whether it alerts in the app and by email.");
        }
        NotificationRule rule = rules.findById(id).orElseThrow(() -> NotFoundException.ofResource("NotificationRule", id));
        String before = AuditJson.of("inApp", rule.isInApp(), "email", rule.isEmail(), "active", rule.isActive());
        rule.configure(body.inApp(), body.email(), body.active());
        rules.save(rule);

        AccessScope scope = scopeResolver.currentScope();
        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role() == null ? null : scope.role().name())
                .action(AuditAction.CONFIGURATION_CHANGED)
                .entity("NotificationRule", rule.getId())
                .before(before)
                .after(AuditJson.of("eventType", rule.getEventType(), "recipientRole", rule.getRecipientRole(),
                        "inApp", rule.isInApp(), "email", rule.isEmail(), "active", rule.isActive()))
                .build());
        return ResponseEntity.ok(toRule(rule));
    }

    private static Item toItem(Notification n) {
        return new Item(n.getId(), n.getEventType(), n.getCategory().name(), n.getTitle(), n.getBody(),
                n.getEntityType(), n.getEntityId(), n.getVesselId(), n.getDueStatus(),
                n.getReadAt() != null, n.getCreatedAt());
    }

    private static RuleView toRule(NotificationRule r) {
        return new RuleView(r.getId(), r.getEventType(), r.getRecipientRole().name(), r.isInApp(), r.isEmail(),
                r.isActive(), r.getSourceRef());
    }

    record Inbox(long unreadCount, List<Item> items) {}

    record UnreadCount(long unreadCount) {}

    record Item(Long id, String eventType, String category, String title, String body,
                String entityType, Long entityId, Long vesselId, String dueStatus,
                boolean read, Instant createdAt) {}

    record DeliveryView(Long id, String channel, String address, String status, int attempts,
                        String lastError, Instant sentAt, Instant createdAt, String eventType, String title) {}

    record RuleView(Long id, String eventType, String recipientRole, boolean inApp, boolean email,
                    boolean active, String sourceRef) {}

    record RuleUpdate(Boolean inApp, Boolean email, Boolean active) {}
}
