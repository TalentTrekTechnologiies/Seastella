package com.seastella.fleet.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.fleet.api.SpareServiceHistory;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.ScopeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Writes a completed service back onto the spare (SRQ-19).
 *
 * <p>Runs inside the transaction that completed the request, so the equipment
 * record and the request can never disagree about whether the work happened.
 * The maintenance cycle is restarted by its own call in the same transaction;
 * this deliberately publishes no event, so one completion does not produce two
 * rounds of maintenance recalculation and two alerts.
 */
@Component
class DefaultSpareServiceHistory implements SpareServiceHistory {

    private static final Logger log = LoggerFactory.getLogger(DefaultSpareServiceHistory.class);

    private final SpareRepository spares;
    private final VesselRepository vessels;
    private final ScopeResolver scopes;
    private final AuditService audit;

    DefaultSpareServiceHistory(SpareRepository spares, VesselRepository vessels, ScopeResolver scopes,
                               AuditService audit) {
        this.spares = spares;
        this.vessels = vessels;
        this.scopes = scopes;
        this.audit = audit;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCompletedService(Long spareId, LocalDate serviceDate, String requestNumber) {
        if (spareId == null || serviceDate == null) return;
        Spare spare = spares.findById(spareId).orElse(null);
        if (spare == null) {
            log.warn("Completed service for a spare that no longer exists: {}", spareId);
            return;
        }
        LocalDate before = spare.getLastAnnualServiceDate();
        if (before != null && !serviceDate.isAfter(before)) {
            // An older date is history, not a correction: the trail keeps it,
            // the spare keeps the most recent service it has had.
            return;
        }
        spare.setLastAnnualServiceDate(serviceDate);
        spares.save(spare);

        AccessScope actor = scopes.currentScope();
        Long organizationId = vessels.findById(spare.getVesselId()).map(Vessel::getOrganizationId).orElse(null);
        audit.record(AuditEntry.builder()
                .actor(actor.userId(), actor.role() == null ? null : actor.role().name())
                .action(AuditAction.SERVICE_DATE_CHANGED)
                .entity("Spare", spare.getId())
                .scope(organizationId, spare.getVesselId())
                .before(AuditJson.of("lastAnnualServiceDate", before))
                .after(AuditJson.of("lastAnnualServiceDate", serviceDate, "serviceRequest", requestNumber))
                .build());
    }
}
