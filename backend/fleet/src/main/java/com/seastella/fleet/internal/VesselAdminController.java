package com.seastella.fleet.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.ForbiddenException;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
import com.seastella.fleet.api.VesselStatus;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.identity.api.UserDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Adding vessels and seeing who runs them (RBAC matrix: create / edit vessel -
 * Platform Admin, Technical Head for own organization).
 *
 * <p>A new vessel receives the <b>standard bridge fit</b> of SoW s9.4 - the VMP
 * template's equipment list in its own decimal structure, EPIRB batteries under
 * EPIRBs and magnetrons under radars. Only the structure is created: make,
 * model, serial number and service dates are the vessel's own facts, left empty
 * until they are entered or imported, never filled with placeholders.
 */
@RestController
@RequestMapping("/api/v1/vessels")
class VesselAdminController {

    private static final Pattern IMO = Pattern.compile("^\\d{7}$");
    private static final Pattern MMSI = Pattern.compile("^\\d{9}$");

    private final VesselRepository vessels;
    private final OrganizationRepository organizations;
    private final SpareRepository spares;
    private final EquipmentCategoryRepository categories;
    private final UserDirectory users;
    private final ScopeResolver scopes;
    private final AuditService audit;

    VesselAdminController(VesselRepository vessels, OrganizationRepository organizations, SpareRepository spares,
                          EquipmentCategoryRepository categories, UserDirectory users,
                          ScopeResolver scopes, AuditService audit) {
        this.vessels = vessels;
        this.organizations = organizations;
        this.spares = spares;
        this.categories = categories;
        this.users = users;
        this.scopes = scopes;
        this.audit = audit;
    }

    /** Vessels in the caller's scope, with the people responsible for each. */
    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TECHNICAL_HEAD','SHIP_MANAGER')")
    @Transactional(readOnly = true)
    ResponseEntity<List<VesselRow>> list() {
        AccessScope scope = scopes.currentScope();
        List<Vessel> found = scope.isPlatformWide() ? vessels.findAll() : vessels.findByIdIn(scope.vesselIds());
        if (found.isEmpty()) return ResponseEntity.ok(List.of());

        Set<Long> ids = found.stream().map(Vessel::getId).collect(Collectors.toSet());
        Map<Long, Long> spareCounts = new HashMap<>();
        spares.countByVessel(ids).forEach(r -> spareCounts.put((Long) r[0], (Long) r[1]));
        Map<Long, String> orgNames = organizations.findAllById(
                        found.stream().map(Vessel::getOrganizationId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Organization::getId, Organization::getName));

        return ResponseEntity.ok(found.stream()
                .sorted(Comparator.comparing(Vessel::getName, String.CASE_INSENSITIVE_ORDER))
                .map(v -> row(v, orgNames.get(v.getOrganizationId()), spareCounts.getOrDefault(v.getId(), 0L)))
                .toList());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TECHNICAL_HEAD')")
    @Transactional
    ResponseEntity<VesselRow> create(@RequestBody VesselForm form) {
        if (form == null) throw new ValidationException("Enter the vessel's details.");
        AccessScope scope = scopes.currentScope();

        // A Technical Head adds vessels to their own fleet only; the
        // organization is taken from their scope, never from the request.
        Long organizationId;
        if (scope.role() == Role.PLATFORM_ADMIN) {
            if (form.organizationId() == null) throw new ValidationException("Choose the organization that operates this vessel.");
            if (!organizations.existsById(form.organizationId())) {
                throw NotFoundException.ofResource("Organization", form.organizationId());
            }
            organizationId = form.organizationId();
        } else {
            if (form.organizationId() != null && !form.organizationId().equals(scope.organizationId())) {
                throw ForbiddenException.ofAction("add vessels to another organization");
            }
            organizationId = scope.organizationId();
        }

        String name = required(form.name(), "Enter the vessel name.", 120);
        String imo = form.imoNumber() == null ? "" : form.imoNumber().trim().replaceFirst("(?i)^IMO\\s*", "");
        if (!IMO.matcher(imo).matches() || !imoCheckDigitValid(imo)) {
            throw new ValidationException("Enter a valid 7-digit IMO number. The last digit is a check digit, "
                    + "so a mistyped number is caught here.");
        }
        if (vessels.existsByImoNumber(imo)) {
            throw new WorkflowException("A vessel with IMO " + imo + " is already on the platform.");
        }
        String mmsi = trimToNull(form.mmsi());
        if (mmsi != null && !MMSI.matcher(mmsi).matches()) {
            throw new ValidationException("MMSI is 9 digits, or leave it blank.");
        }
        if (form.dwt() != null && (form.dwt().signum() < 0 || form.dwt().compareTo(new BigDecimal("9999999999")) > 0)) {
            throw new ValidationException("Enter the deadweight in tonnes, or leave it blank.");
        }

        Vessel vessel = new Vessel(organizationId, name, imo);
        vessel.setMmsi(mmsi);
        vessel.setCallSign(optional(form.callSign(), 16, "call sign"));
        vessel.setFlag(optional(form.flag(), 64, "flag"));
        vessel.setVesselClass(optional(form.vesselClass(), 64, "class society"));
        vessel.setArea(optional(form.area(), 64, "trading area"));
        vessel.setVesselType(optional(form.vesselType(), 64, "vessel type"));
        vessel.setDwt(form.dwt());
        vessel.setStatus(form.status() == null ? VesselStatus.ACTIVE : form.status());
        vessels.save(vessel);

        int fitted = applyStandardFit(vessel.getId());

        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role().name())
                .action(AuditAction.VESSEL_CREATED)
                .entity("Vessel", vessel.getId())
                .scope(organizationId, vessel.getId())
                .after(AuditJson.of("name", name, "imoNumber", imo, "mmsi", mmsi, "vesselType", vessel.getVesselType(),
                        "flag", vessel.getFlag(), "status", vessel.getStatus().name(), "standardFitSpares", fitted))
                .build());

        String orgName = organizations.findById(organizationId).map(Organization::getName).orElse(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(row(vessel, orgName, fitted));
    }

    /** SoW s9.4, parented by decimal path exactly as the VMP template nests it. */
    private int applyStandardFit(Long vesselId) {
        Map<String, Long> categoryIds = categories.findAll().stream()
                .collect(Collectors.toMap(EquipmentCategory::getCode, EquipmentCategory::getId));
        Map<String, Long> byPath = new HashMap<>();
        int count = 0;

        for (FleetSeedContributor.SpareSpec spec : FleetSeedCatalogue.SPARES) {
            Long categoryId = categoryIds.get(spec.categoryCode());
            if (categoryId == null) continue;

            Spare spare = new Spare(vesselId, categoryId, spec.path(), spec.name());
            spare.setCriticality(spec.criticality());
            if (spec.runningHours()) {
                spare.enableRunningHours(null);   // no reading until one is taken aboard
            }
            int lastDot = spec.path().lastIndexOf('.');
            if (lastDot > 0) {
                spare.setParentSpareId(byPath.get(spec.path().substring(0, lastDot)));
            }
            byPath.put(spec.path(), spares.save(spare).getId());
            count++;
        }
        return count;
    }

    private VesselRow row(Vessel v, String organizationName, long spareCount) {
        List<Person> managers = users.activeOnVessel(Role.SHIP_MANAGER, v.getId()).stream()
                .map(u -> new Person(u.id(), u.fullName())).toList();
        Person captain = users.activeOnVessel(Role.CAPTAIN, v.getId()).stream()
                .map(u -> new Person(u.id(), u.fullName())).findFirst().orElse(null);
        return new VesselRow(v.getId(), v.getName(), v.getImoNumber(), v.getVesselType(), v.getFlag(),
                v.getStatus().name(), v.getOrganizationId(), organizationName, spareCount,
                managers.isEmpty() ? null : managers.get(0), captain, v.getCreatedAt());
    }

    /** IMO numbers carry a check digit: digits 1-6 weighted 7 down to 2, sum mod 10. */
    static boolean imoCheckDigitValid(String imo) {
        int sum = 0;
        for (int i = 0; i < 6; i++) {
            sum += (imo.charAt(i) - '0') * (7 - i);
        }
        return sum % 10 == imo.charAt(6) - '0';
    }

    private static String required(String value, String message, int max) {
        String t = trimToNull(value);
        if (t == null) throw new ValidationException(message);
        if (t.length() > max) throw new ValidationException("Keep the name under " + max + " characters.");
        return t;
    }

    private static String optional(String value, int max, String what) {
        String t = trimToNull(value);
        if (t != null && t.length() > max) {
            throw new ValidationException("Keep the " + what + " under " + max + " characters.");
        }
        return t;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    record VesselForm(Long organizationId, String name, String imoNumber, String mmsi, String callSign,
                      String flag, String vesselClass, String area, String vesselType, BigDecimal dwt,
                      VesselStatus status) {}

    record Person(Long id, String fullName) {}

    record VesselRow(Long id, String name, String imoNumber, String vesselType, String flag, String status,
                     Long organizationId, String organizationName, long spareCount,
                     Person shipManager, Person captain, Instant createdAt) {}
}
