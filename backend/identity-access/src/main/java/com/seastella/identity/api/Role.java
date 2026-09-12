package com.seastella.identity.api;

/**
 * The six pilot roles, fixed by SoW section 5.
 *
 * <p>This enum is closed on purpose. The master brief is explicit - "DO NOT
 * invent roles" - and the SoW fixes the pilot set. {@link #CHIEF_ENGINEER} is
 * declared but {@link #enabled} is false: it is a Phase-2 role, present so that
 * enabling it later needs no migration, and blocked from provisioning today
 * (test S-16).
 */
public enum Role {

    /** Entire platform. Creates organizations and their Technical Head. */
    PLATFORM_ADMIN(ScopeKind.PLATFORM, true),

    /** All vessels in own organization. Creates Ship Managers, allocates vessels. */
    TECHNICAL_HEAD(ScopeKind.ORGANIZATION, true),

    /** Only vessels allocated by the Technical Head. Approves requests and invoices. */
    SHIP_MANAGER(ScopeKind.VESSEL_SET, true),

    /** Exactly one assigned vessel. Raises service requests. */
    CAPTAIN(ScopeKind.VESSEL_SET, true),

    /**
     * Seastella service operations. Triage, invoicing, engineer assignment.
     *
     * <p>Platform-side, not a client tenant: SoW section 5 labels the role
     * "Service Coordinator (Seastella)" and scopes it to an "Assigned
     * service scope" rather than an organization. Scoped to an explicitly
     * assigned set of client organizations (OI-16).
     */
    SERVICE_COORDINATOR(ScopeKind.ORGANIZATION_SET, true),

    /**
     * Assigned jobs only. Reports completion to the Coordinator alone.
     *
     * <p>Also platform-side - SoW section 5 maps it to "Service Provider /
     * Technician", an external vendor. The job stays the lowest-level
     * assignment boundary, so no organization assignment is needed or
     * granted: an engineer's reach is exactly their own jobs.
     */
    SERVICE_ENGINEER(ScopeKind.JOB_SET, true),

    /** Phase 2 (SoW section 5). Declared, not provisionable. */
    CHIEF_ENGINEER(ScopeKind.VESSEL_SET, false);

    private final ScopeKind scopeKind;
    private final boolean enabled;

    Role(ScopeKind scopeKind, boolean enabled) {
        this.scopeKind = scopeKind;
        this.enabled = enabled;
    }

    public ScopeKind scopeKind() { return scopeKind; }

    /** False for roles defined but out of pilot scope. */
    public boolean isEnabled() { return enabled; }

    /** Spring Security authority name. */
    public String authority() { return "ROLE_" + name(); }

    /**
     * Whether this role belongs to Seastella or a service provider rather than
     * to a client organization.
     *
     * <p>Platform-side users carry no {@code organization_id}: a Platform Admin
     * has none at all, and a Coordinator or Engineer is scoped by assignment
     * instead. The database CHECK on {@code app_user} enforces exactly this.
     */
    public boolean isPlatformSide() {
        return this == PLATFORM_ADMIN
                || this == SERVICE_COORDINATOR
                || this == SERVICE_ENGINEER;
    }

    /**
     * Whether this role may see monetary values on invoices.
     *
     * <p>SoW section 12: "invoice/cost data is visible only to the Ship Manager,
     * Service Coordinator, Technical Head and Platform Admin - not the Captain
     * or Service Engineer." Tests S-20 through S-22.
     */
    public boolean canSeeFinancials() {
        return this == PLATFORM_ADMIN
                || this == TECHNICAL_HEAD
                || this == SHIP_MANAGER
                || this == SERVICE_COORDINATOR;
    }
}
