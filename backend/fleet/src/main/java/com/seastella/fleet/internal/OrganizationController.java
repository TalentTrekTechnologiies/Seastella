package com.seastella.fleet.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
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

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Client organizations (SoW s4.1 step 1, IAM-02).
 *
 * <p>Only the Platform Admin creates one. A Technical Head may read their own,
 * nothing else. The organization's Technical Head is then created through
 * {@code POST /api/v1/users}, which keeps the grant rules in one place.
 */
@RestController
@RequestMapping("/api/v1/organizations")
class OrganizationController {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9]{2,12}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");

    private final OrganizationRepository organizations;
    private final VesselRepository vessels;
    private final UserDirectory users;
    private final ScopeResolver scopes;
    private final AuditService audit;

    OrganizationController(OrganizationRepository organizations, VesselRepository vessels,
                           UserDirectory users, ScopeResolver scopes, AuditService audit) {
        this.organizations = organizations;
        this.vessels = vessels;
        this.users = users;
        this.scopes = scopes;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TECHNICAL_HEAD')")
    @Transactional(readOnly = true)
    ResponseEntity<List<OrganizationRow>> list() {
        AccessScope scope = scopes.currentScope();
        List<Organization> found = scope.role() == Role.PLATFORM_ADMIN
                ? organizations.findAll()
                : organizations.findById(scope.organizationId()).stream().toList();
        return ResponseEntity.ok(found.stream()
                .sorted(Comparator.comparing(Organization::getName, String.CASE_INSENSITIVE_ORDER))
                .map(this::row)
                .toList());
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Transactional
    ResponseEntity<OrganizationRow> create(@RequestBody OrganizationForm form) {
        if (form == null) throw new ValidationException("Enter the organization's details.");
        String name = required(form.name(), "Enter the organization's name.", 160);
        String code = form.code() == null ? "" : form.code().trim().toUpperCase();
        if (!CODE.matcher(code).matches()) {
            throw new ValidationException("The short code is 2 to 12 letters or digits, e.g. ACME. It appears in request and invoice numbers.");
        }
        if (organizations.existsByCode(code)) {
            throw new WorkflowException("An organization already uses the code " + code + ".");
        }
        String email = trimToNull(form.contactEmail());
        if (email != null && (email.length() > 254 || !EMAIL.matcher(email).matches())) {
            throw new ValidationException("Enter a valid contact email, or leave it blank.");
        }

        Organization org = new Organization(name, code);
        org.setAddress(limit(trimToNull(form.address()), 400, "address"));
        org.setContactEmail(email);
        org.setContactPhone(limit(trimToNull(form.contactPhone()), 40, "phone number"));
        organizations.save(org);

        AccessScope scope = scopes.currentScope();
        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role().name())
                .action(AuditAction.ORGANIZATION_CREATED)
                .entity("Organization", org.getId())
                .scope(org.getId(), null)
                .after(AuditJson.of("name", name, "code", code, "contactEmail", email))
                .build());

        return ResponseEntity.status(HttpStatus.CREATED).body(row(org));
    }

    private OrganizationRow row(Organization o) {
        List<Person> heads = users.activeForOrganization(Role.TECHNICAL_HEAD, o.getId()).stream()
                .map(u -> new Person(u.id(), u.fullName(), u.email()))
                .toList();
        return new OrganizationRow(o.getId(), o.getName(), o.getCode(), o.getAddress(), o.getContactEmail(),
                o.getContactPhone(), o.getStatus(), vessels.countByOrganizationId(o.getId()),
                users.activeForOrganization(Role.SHIP_MANAGER, o.getId()).size(),
                heads, o.getCreatedAt());
    }

    private static String required(String value, String message, int max) {
        String t = trimToNull(value);
        if (t == null) throw new ValidationException(message);
        return limit(t, max, "name");
    }

    private static String limit(String value, int max, String what) {
        if (value != null && value.length() > max) {
            throw new ValidationException("Keep the " + what + " under " + max + " characters.");
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    record OrganizationForm(String name, String code, String address, String contactEmail, String contactPhone) {}

    record Person(Long id, String fullName, String email) {}

    record OrganizationRow(Long id, String name, String code, String address, String contactEmail,
                           String contactPhone, String status, long vesselCount, long shipManagerCount,
                           List<Person> technicalHeads, Instant createdAt) {}
}
