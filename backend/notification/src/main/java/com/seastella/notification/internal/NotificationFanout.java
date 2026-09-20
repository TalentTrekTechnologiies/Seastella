package com.seastella.notification.internal;

import com.seastella.fleet.api.FleetEvents;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.UserDirectory;
import com.seastella.identity.api.UserDirectory.UserRef;
import com.seastella.maintenance.api.MaintenanceEvents;
import com.seastella.servicerequest.api.ServiceRequestEvents;
import com.seastella.servicerequest.api.ServiceRequestMetrics;
import com.seastella.servicerequest.api.ServiceRequestMetrics.RequestSummary;
import com.seastella.troubleshooting.api.TroubleshootingEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns domain events into alerts for the right people (SoW s11).
 *
 * <p>Runs <b>after commit</b>, so nobody is told about an approval that rolled
 * back, and in a transaction of its own, so a failure here is logged rather
 * than turning a committed approval into an error for the person who made it.
 *
 * <p>Recipients come from scope, never from the event alone: the Ship Managers
 * and Captain assigned to <em>this</em> vessel, the Technical Head of
 * <em>this</em> organization, the Coordinators assigned to serve it, and only
 * the engineer assigned to <em>this</em> job. The person who acted is not
 * alerted about their own action.
 */
@Component
class NotificationFanout {

    static final String MAINTENANCE_DUE = "MAINTENANCE_DUE";
    static final String PART_SHORTAGE = "PART_SHORTAGE";

    private static final Logger log = LoggerFactory.getLogger(NotificationFanout.class);

    private final NotificationRuleRepository rules;
    private final NotificationRepository notifications;
    private final NotificationDeliveryRepository deliveries;
    private final UserDirectory users;
    private final ServiceRequestMetrics requests;
    private final TransactionTemplate tx;

    NotificationFanout(NotificationRuleRepository rules, NotificationRepository notifications,
                       NotificationDeliveryRepository deliveries, UserDirectory users,
                       ServiceRequestMetrics requests, PlatformTransactionManager transactions) {
        this.rules = rules;
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.users = users;
        this.requests = requests;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransition(ServiceRequestEvents.Transitioned event) {
        try {
            tx.executeWithoutResult(status -> requestAlerts(event));
        } catch (RuntimeException e) {
            log.error("Alerts for {} {} could not be created", event.requestNumber(), event.action(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDueStatusChanged(MaintenanceEvents.DueStatusChanged event) {
        try {
            tx.executeWithoutResult(status -> maintenanceAlerts(event));
        } catch (RuntimeException e) {
            log.error("Maintenance alerts for vessel {} could not be created", event.vesselId(), e);
        }
    }

    private void requestAlerts(ServiceRequestEvents.Transitioned event) {
        List<NotificationRule> applicable = rules.findByEventTypeAndActiveTrue(event.action().name());
        if (applicable.isEmpty()) return;

        RequestSummary request = requests.summary(event.serviceRequestId()).orElse(null);
        if (request == null) return;

        String actor = users.find(event.actorUserId()).map(UserRef::fullName).orElse("Someone");
        AlertMessages.RequestContext context = new AlertMessages.RequestContext(
                request.requestNumber(), request.spareName(), request.vesselName(), request.title(),
                actor, event.reason());

        for (NotificationRule rule : applicable) {
            AlertMessages.Message message = AlertMessages.forRequest(event.action(), rule.getRecipientRole(), context);
            if (message == null) continue;

            List<UserRef> recipients = switch (rule.getRecipientRole()) {
                case SERVICE_ENGINEER -> users.find(request.assignedEngineerUserId())
                        .filter(u -> u.active() && u.role() == Role.SERVICE_ENGINEER)
                        .stream().toList();
                default -> recipientsInScope(rule.getRecipientRole(), event.organizationId(), event.vesselId());
            };

            for (UserRef user : recipients) {
                if (Objects.equals(user.id(), event.actorUserId())) continue;
                create(user, rule, event.action().name(), message, "SERVICE_REQUEST", event.serviceRequestId(),
                        event.organizationId(), event.vesselId(), null);
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTroubleshootingCompleted(TroubleshootingEvents.Completed event) {
        try {
            tx.executeWithoutResult(status -> troubleshootingAlerts(event));
        } catch (RuntimeException e) {
            log.error("Troubleshooting alerts for {} could not be created", event.requestNumber(), e);
        }
    }

    private void troubleshootingAlerts(TroubleshootingEvents.Completed event) {
        List<NotificationRule> applicable = rules.findByEventTypeAndActiveTrue(event.eventType());
        if (applicable.isEmpty()) return;

        RequestSummary request = requests.summary(event.serviceRequestId()).orElse(null);
        if (request == null) return;
        AlertMessages.RequestContext context = new AlertMessages.RequestContext(
                request.requestNumber(), request.spareName(), request.vesselName(), request.title(),
                users.find(event.actorUserId()).map(UserRef::fullName).orElse("Someone"), null);

        for (NotificationRule rule : applicable) {
            AlertMessages.Message message = AlertMessages.forTroubleshooting(event.outcome(), rule.getRecipientRole(), context);
            if (message == null) continue;
            for (UserRef user : recipientsInScope(rule.getRecipientRole(), event.organizationId(), event.vesselId())) {
                if (Objects.equals(user.id(), event.actorUserId())) continue;
                create(user, rule, event.eventType(), message, "SERVICE_REQUEST", event.serviceRequestId(),
                        event.organizationId(), event.vesselId(), null);
            }
        }
    }

    private void maintenanceAlerts(MaintenanceEvents.DueStatusChanged event) {
        List<NotificationRule> applicable = rules.findByEventTypeAndActiveTrue(MAINTENANCE_DUE);
        if (applicable.isEmpty()) return;

        AlertMessages.Message message = AlertMessages.forMaintenance(event);
        boolean single = event.changes().size() == 1;
        String entityType = single ? "SPARE" : "VESSEL";
        Long entityId = single ? event.changes().get(0).spareId() : event.vesselId();

        // A user can hold only one role, but guard against a repeat regardless.
        Map<Long, NotificationRule> byUser = new LinkedHashMap<>();
        Map<Long, UserRef> people = new LinkedHashMap<>();
        for (NotificationRule rule : applicable) {
            for (UserRef user : recipientsInScope(rule.getRecipientRole(), event.organizationId(), event.vesselId())) {
                byUser.putIfAbsent(user.id(), rule);
                people.putIfAbsent(user.id(), user);
            }
        }
        byUser.forEach((userId, rule) -> create(people.get(userId), rule, MAINTENANCE_DUE, message,
                entityType, entityId, event.organizationId(), event.vesselId(), event.worst().name()));
    }

    /** A stock count took a replacement part below the minimum to hold (SPR-14). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPartStockChanged(FleetEvents.PartStockChanged event) {
        if (!event.nowBelowMinimum()) return;
        try {
            tx.executeWithoutResult(status -> partShortageAlerts(event));
        } catch (RuntimeException e) {
            log.error("Shortage alerts for part {} could not be created", event.partId(), e);
        }
    }

    private void partShortageAlerts(FleetEvents.PartStockChanged event) {
        List<NotificationRule> applicable = rules.findByEventTypeAndActiveTrue(PART_SHORTAGE);
        if (applicable.isEmpty()) return;

        AlertMessages.Message message = AlertMessages.forPartShortage(event);
        Map<Long, NotificationRule> byUser = new LinkedHashMap<>();
        Map<Long, UserRef> people = new LinkedHashMap<>();
        for (NotificationRule rule : applicable) {
            for (UserRef user : recipientsInScope(rule.getRecipientRole(), event.organizationId(), event.vesselId())) {
                byUser.putIfAbsent(user.id(), rule);
                people.putIfAbsent(user.id(), user);
            }
        }
        byUser.forEach((userId, rule) -> create(people.get(userId), rule, PART_SHORTAGE, message,
                "REPLACEMENT_PART", event.partId(), event.organizationId(), event.vesselId(), null));
    }

    /**
     * Certificate expiry reminders (NOT-11). Raised by the nightly scan rather
     * than by a domain event, but told to the same people, by the same rules.
     */
    void certificateAlert(com.seastella.fleet.api.DocumentDirectory.CertificateRef certificate,
                          AlertMessages.Message message, List<Role> roles) {
        Map<Long, NotificationRule> byUser = new LinkedHashMap<>();
        Map<Long, UserRef> people = new LinkedHashMap<>();
        for (NotificationRule rule : rules.findByEventTypeAndActiveTrue(CertificateExpiryMonitor.EVENT_TYPE)) {
            if (!roles.contains(rule.getRecipientRole())) continue;
            for (UserRef user : recipientsInScope(rule.getRecipientRole(),
                    certificate.organizationId(), certificate.vesselId())) {
                byUser.putIfAbsent(user.id(), rule);
                people.putIfAbsent(user.id(), user);
            }
        }
        byUser.forEach((userId, rule) -> create(people.get(userId), rule, CertificateExpiryMonitor.EVENT_TYPE,
                message, "DOCUMENT", certificate.id(), certificate.organizationId(), certificate.vesselId(), null));
    }

    private List<UserRef> recipientsInScope(Role role, Long organizationId, Long vesselId) {
        return switch (role) {
            case SHIP_MANAGER, CAPTAIN -> users.activeOnVessel(role, vesselId);
            case TECHNICAL_HEAD, SERVICE_COORDINATOR -> users.activeForOrganization(role, organizationId);
            case PLATFORM_ADMIN -> users.activeByRole(Role.PLATFORM_ADMIN);
            default -> List.of();
        };
    }

    private void create(UserRef user, NotificationRule rule, String eventType, AlertMessages.Message message,
                        String entityType, Long entityId, Long organizationId, Long vesselId, String dueStatus) {
        if (!rule.isInApp() && !rule.isEmail()) return;

        Notification notification = notifications.save(new Notification(user.id(), eventType,
                message.category(), message.title(), message.body(), entityType, entityId,
                organizationId, vesselId, dueStatus, rule.isInApp()));

        if (rule.isEmail() && user.email() != null && !user.email().isBlank()) {
            deliveries.save(new NotificationDelivery(notification.getId(), NotificationDelivery.EMAIL, user.email()));
        }
    }
}
