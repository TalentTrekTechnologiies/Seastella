package com.seastella.maintenance.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.event.DomainEventPublisher;
import com.seastella.fleet.api.FleetDirectory;
import com.seastella.maintenance.api.DueAssessment;
import com.seastella.maintenance.api.DueStatus;
import com.seastella.maintenance.api.MaintenanceEvents;
import com.seastella.maintenance.api.MaintenanceStatusEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Watches for colour-status changes and announces them (SoW s11, NOT-10).
 *
 * <p>Status is derived on read everywhere; nothing here changes what a
 * dashboard shows. This class only remembers the last status it saw per spare,
 * so that it can tell "became overdue" from "is still overdue". It alerts when
 * a spare moves into a <em>more severe</em> band - approaching, urgent, due or
 * overdue. Recovering to normal after a service is recorded but not announced.
 *
 * <p>Runs at startup, every night shortly after midnight UTC (when day counts
 * roll over), and straight after anything that moves a due date: a running-hour
 * reading or a completed service.
 */
@Service
class DueStatusMonitor {

    private static final Logger log = LoggerFactory.getLogger(DueStatusMonitor.class);

    private final SpareMaintenanceRuleRepository rules;
    private final SpareDueStateRepository states;
    private final MaintenanceStatusEngine engine;
    private final FleetDirectory fleet;
    private final DomainEventPublisher events;
    private final AuditService audit;
    private final TransactionTemplate tx;

    DueStatusMonitor(SpareMaintenanceRuleRepository rules, SpareDueStateRepository states,
                     MaintenanceStatusEngine engine, FleetDirectory fleet,
                     DomainEventPublisher events, AuditService audit, PlatformTransactionManager transactions) {
        this.rules = rules;
        this.states = states;
        this.engine = engine;
        this.fleet = fleet;
        this.events = events;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        scanFleet("startup");
    }

    @Scheduled(cron = "${seastella.maintenance.status-scan-cron:0 5 0 * * *}", zone = "UTC")
    public void nightly() {
        scanFleet("nightly");
    }

    /** Re-checks spares whose rules just changed, after that change committed. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRulesChanged(RulesChanged change) {
        try {
            int alerts = scan(change.spareIds());
            log.debug("Maintenance status re-checked for spares {}: {} vessel alert(s)", change.spareIds(), alerts);
        } catch (RuntimeException e) {
            // The reading or completion has already committed; a failed
            // re-check must not turn that success into an error response.
            log.error("Maintenance status re-check failed for spares {}", change.spareIds(), e);
        }
    }

    private void scanFleet(String trigger) {
        try {
            int alerts = scan(rules.findActiveSpareIds());
            log.info("Maintenance status scan ({}): {} vessel alert(s) raised", trigger, alerts);
        } catch (RuntimeException e) {
            log.error("Maintenance status scan ({}) failed", trigger, e);
        }
    }

    /**
     * Evaluates the spares in a transaction of its own and publishes one
     * {@link MaintenanceEvents.DueStatusChanged} per vessel with escalations.
     *
     * @return the number of vessel alerts published
     */
    int scan(Collection<Long> spareIds) {
        if (spareIds == null || spareIds.isEmpty()) return 0;
        Integer published = tx.execute(status -> evaluate(new LinkedHashSet<>(spareIds)));
        return published == null ? 0 : published;
    }

    private int evaluate(Collection<Long> spareIds) {
        Map<Long, SpareDueState> known = states.findBySpareIdIn(spareIds).stream()
                .collect(Collectors.toMap(SpareDueState::getSpareId, Function.identity()));

        LocalDate today = LocalDate.now();
        Instant now = Instant.now();
        Map<Long, List<MaintenanceEvents.Change>> escalations = new LinkedHashMap<>();
        Map<Long, FleetDirectory.SpareRef> vesselRefs = new LinkedHashMap<>();

        for (Long spareId : spareIds) {
            FleetDirectory.SpareRef ref = fleet.spareRef(spareId).orElse(null);
            if (ref == null) continue;

            DueAssessment assessment = engine.assess(spareId, today);
            SpareDueState state = known.get(spareId);
            DueStatus previous = state == null ? null : state.getStatus();
            if (state == null) {
                state = new SpareDueState(spareId, ref.vesselId());
            }

            boolean escalated = assessment.status().needsAttention()
                    && MaintenanceEvents.severity(assessment.status()) > MaintenanceEvents.severity(previous);

            state.observe(assessment.status(), assessment.nextDueDate(), assessment.basis().name(), now);
            states.save(state);

            if (escalated) {
                escalations.computeIfAbsent(ref.vesselId(), v -> new ArrayList<>()).add(
                        new MaintenanceEvents.Change(spareId, ref.name(), ref.path(), previous,
                                assessment.status(), assessment.daysRemaining(), assessment.nextDueDate(),
                                assessment.basis()));
                vesselRefs.putIfAbsent(ref.vesselId(), ref);
            }
        }

        escalations.forEach((vesselId, changes) -> {
            FleetDirectory.SpareRef ref = vesselRefs.get(vesselId);
            MaintenanceEvents.DueStatusChanged event = new MaintenanceEvents.DueStatusChanged(
                    ref.organizationId(), vesselId, ref.vesselName(), changes, now);
            // No actor: the platform noticed this, nobody did it. Recorded so the
            // Platform Admin's feed shows it with everything else (SoW s11).
            audit.record(AuditEntry.builder()
                    .action(AuditAction.MAINTENANCE_STATUS_CHANGED)
                    .entity("Vessel", vesselId)
                    .scope(ref.organizationId(), vesselId)
                    .after(AuditJson.of("spares", changes.size(), "worst", event.worst().name(),
                            "names", changes.stream().limit(3).map(MaintenanceEvents.Change::spareName)
                                    .collect(Collectors.joining(", "))))
                    .occurredAt(now)
                    .build());
            events.publish(event);
        });
        return escalations.size();
    }
}
