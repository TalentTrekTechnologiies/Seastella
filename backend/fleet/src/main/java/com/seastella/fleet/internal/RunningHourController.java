package com.seastella.fleet.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.event.DomainEventPublisher;
import com.seastella.fleet.api.FleetEvents;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.ScopeGuard;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.identity.api.UserDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Running-hour capture (SoW s7, RHR-01).
 *
 * <p>RBAC matrix: <i>Record running hours</i> belongs to the Platform Admin and
 * to the Captain on their own vessel. Reading the history needs only the vessel
 * in scope. Out-of-scope spares answer 404, never 403.
 *
 * <p>A reading is checked against physics before it is accepted: an hour meter
 * does not run backwards and cannot gain more than 24 hours a day. A typo that
 * passed those checks would move a magnetron's due date by months, and nobody
 * would see why.
 */
@RestController
@RequestMapping("/api/v1/spares")
class RunningHourController {

    private static final BigDecimal HOURS_PER_DAY = new BigDecimal("24");
    private static final BigDecimal MAX_READING = new BigDecimal("9999999999.99");

    private final SpareRepository spares;
    private final VesselRepository vessels;
    private final RunningHourReadingRepository readings;
    private final ScopeGuard scopeGuard;
    private final ScopeResolver scopeResolver;
    private final UserDirectory users;
    private final AuditService audit;
    private final DomainEventPublisher events;

    RunningHourController(SpareRepository spares, VesselRepository vessels,
                          RunningHourReadingRepository readings, ScopeGuard scopeGuard,
                          ScopeResolver scopeResolver, UserDirectory users,
                          AuditService audit, DomainEventPublisher events) {
        this.spares = spares;
        this.vessels = vessels;
        this.readings = readings;
        this.scopeGuard = scopeGuard;
        this.scopeResolver = scopeResolver;
        this.users = users;
        this.audit = audit;
        this.events = events;
    }

    @GetMapping("/{spareId}/running-hours")
    @Transactional(readOnly = true)
    ResponseEntity<HourHistory> history(@PathVariable Long spareId) {
        Spare spare = spareInScope(spareId);
        return ResponseEntity.ok(historyOf(spare));
    }

    @PostMapping("/{spareId}/running-hours")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','CAPTAIN')")
    @Transactional
    ResponseEntity<HourHistory> record(@PathVariable Long spareId, @RequestBody RecordReading body) {
        Spare spare = spareInScope(spareId);
        AccessScope scope = scopeResolver.currentScope();

        if (!spare.isTracksRunningHours()) {
            throw new ValidationException(spare.getName() + " does not track running hours.");
        }
        if (body == null || body.readingHours() == null) {
            throw new ValidationException("Enter the hour meter reading.");
        }
        BigDecimal reading = body.readingHours();
        if (reading.signum() < 0 || reading.compareTo(MAX_READING) > 0) {
            throw new ValidationException("Enter a reading between 0 and 9,999,999,999 hours.");
        }
        if (reading.stripTrailingZeros().scale() > 2) {
            throw new ValidationException("Use at most two decimal places.");
        }

        // The vessel's local date is not known here; allowing one day ahead of
        // UTC keeps a ship east of Greenwich from being told its today is "future".
        LocalDate readingDate = body.readingDate() == null ? LocalDate.now(ZoneOffset.UTC) : body.readingDate();
        if (readingDate.isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1))) {
            throw new ValidationException("The reading date cannot be in the future.");
        }

        String note = body.note() == null || body.note().isBlank() ? null : body.note().trim();
        if (note != null && note.length() > 500) {
            throw new ValidationException("Keep the note under 500 characters.");
        }

        RunningHourReading last = readings.findFirstBySpareIdOrderByReadingDateDescIdDesc(spareId).orElse(null);
        BigDecimal previous = last != null ? last.getReadingHours() : spare.getRunningHours();

        if (last != null && readingDate.isBefore(last.getReadingDate())) {
            throw new ValidationException("The last reading was taken on " + last.getReadingDate()
                    + ". Readings are recorded in order, so this one cannot be dated earlier.");
        }
        if (previous != null && reading.compareTo(previous) < 0) {
            throw new ValidationException("This is lower than the last recorded " + previous.toPlainString()
                    + " h. Hour meters do not run backwards; a replaced unit is reset through its completed service.");
        }
        if (last != null && previous != null) {
            long days = Math.max(1, ChronoUnit.DAYS.between(last.getReadingDate(), readingDate));
            BigDecimal possible = HOURS_PER_DAY.multiply(BigDecimal.valueOf(days));
            if (reading.subtract(previous).compareTo(possible) > 0) {
                throw new ValidationException(reading.subtract(previous).toPlainString() + " h in " + days
                        + (days == 1 ? " day" : " days") + " is more than the meter can run (24 h a day). Check the reading.");
            }
        }

        readings.save(new RunningHourReading(spareId, spare.getVesselId(), reading, previous,
                readingDate, scope.userId(), note));
        spare.updateRunningHours(reading);
        spares.save(spare);

        Long organizationId = vessels.findById(spare.getVesselId()).map(Vessel::getOrganizationId).orElse(null);

        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role() == null ? null : scope.role().name())
                .action(AuditAction.RUNNING_HOURS_RECORDED)
                .entity("Spare", spareId)
                .scope(organizationId, spare.getVesselId())
                .before(AuditJson.of("runningHours", previous == null ? null : previous.toPlainString()))
                .after(AuditJson.of("runningHours", reading.toPlainString(), "readingDate", readingDate, "note", note))
                .build());

        events.publish(new FleetEvents.RunningHoursRecorded(
                spareId, spare.getName(), spare.getVesselId(), organizationId,
                previous, reading, readingDate, scope.userId(), Instant.now()));

        return ResponseEntity.status(HttpStatus.CREATED).body(historyOf(spare));
    }

    private Spare spareInScope(Long spareId) {
        Spare spare = spares.findById(spareId)
                .orElseThrow(() -> NotFoundException.ofResource("Spare", spareId));
        scopeGuard.assertVessel(spare.getVesselId());
        return spare;
    }

    private HourHistory historyOf(Spare spare) {
        List<RunningHourReading> recent = readings.findTop24BySpareIdOrderByReadingDateDescIdDesc(spare.getId());
        Map<Long, UserDirectory.UserRef> names = users.findAll(
                recent.stream().map(RunningHourReading::getRecordedByUserId).toList());

        List<Reading> items = recent.stream()
                .map(r -> new Reading(r.getId(), r.getReadingDate(), r.getReadingHours(),
                        r.getPreviousReadingHours(),
                        r.getPreviousReadingHours() == null ? null : r.getReadingHours().subtract(r.getPreviousReadingHours()),
                        names.containsKey(r.getRecordedByUserId()) ? names.get(r.getRecordedByUserId()).fullName() : null,
                        r.getNote(), r.getCreatedAt()))
                .toList();

        String vesselName = vessels.findById(spare.getVesselId()).map(Vessel::getName).orElse(null);
        return new HourHistory(spare.getId(), spare.getName(), spare.getPath(), spare.getVesselId(), vesselName,
                spare.isTracksRunningHours(), spare.getRunningHours(),
                recent.isEmpty() ? null : recent.get(0).getReadingDate(), items);
    }

    record RecordReading(BigDecimal readingHours, LocalDate readingDate, String note) {}

    record HourHistory(Long spareId, String spareName, String sparePath, Long vesselId, String vesselName,
                       boolean tracksRunningHours, BigDecimal currentHours, LocalDate lastReadingDate,
                       List<Reading> readings) {}

    record Reading(Long id, LocalDate readingDate, BigDecimal readingHours, BigDecimal previousHours,
                   BigDecimal hoursAdded, String recordedBy, String note, Instant recordedAt) {}
}
