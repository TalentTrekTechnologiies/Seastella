package com.seastella.reporting.api;

import com.seastella.identity.api.Role;

import java.util.List;
import java.util.Optional;

/**
 * The reports SoW s7 names, and who may run each.
 *
 * <p>Every report runs against the caller's own scope, so the same key gives a
 * Ship Manager their two vessels and a Technical Head the whole fleet (RPT-09).
 * A role that is not listed cannot run it at all.
 */
public enum ReportCatalogue {

    VESSEL_SPARES("vessel-spares", "Vessel equipment",
            "Every spare with its make, model, serial number, hours and working status",
            List.of(Role.PLATFORM_ADMIN, Role.TECHNICAL_HEAD, Role.SHIP_MANAGER, Role.CAPTAIN)),

    SERVICE_DUE("service-due", "Service due",
            "What is approaching, due or overdue, with days remaining and its colour",
            List.of(Role.PLATFORM_ADMIN, Role.TECHNICAL_HEAD, Role.SHIP_MANAGER, Role.CAPTAIN)),

    CERTIFICATES("certificates", "Certificate expiry",
            "Certificates on file, soonest to expire first",
            List.of(Role.PLATFORM_ADMIN, Role.TECHNICAL_HEAD, Role.SHIP_MANAGER, Role.CAPTAIN)),

    TROUBLESHOOTING("troubleshooting", "Troubleshooting",
            "Guided checks run on each request, and what they concluded",
            List.of(Role.PLATFORM_ADMIN, Role.TECHNICAL_HEAD, Role.SHIP_MANAGER, Role.SERVICE_COORDINATOR)),

    /** SoW s12 keeps cost from the Captain and the Engineer, so neither can run it. */
    INVOICES("invoices", "Invoices and cost",
            "Invoices raised, their status and value",
            List.of(Role.PLATFORM_ADMIN, Role.TECHNICAL_HEAD, Role.SHIP_MANAGER, Role.SERVICE_COORDINATOR)),

    FLEET_SUMMARY("fleet-summary", "Fleet technical summary",
            "One line per vessel: equipment, what needs attention, open requests",
            List.of(Role.PLATFORM_ADMIN, Role.TECHNICAL_HEAD, Role.SHIP_MANAGER)),

    PARTS_INVENTORY("parts-inventory", "Replacement parts",
            "Parts held on board, with anything below its minimum marked",
            List.of(Role.PLATFORM_ADMIN, Role.TECHNICAL_HEAD, Role.SHIP_MANAGER, Role.CAPTAIN));

    private final String key;
    private final String title;
    private final String description;
    private final List<Role> roles;

    ReportCatalogue(String key, String title, String description, List<Role> roles) {
        this.key = key;
        this.title = title;
        this.description = description;
        this.roles = roles;
    }

    public String key() { return key; }
    public String title() { return title; }
    public String description() { return description; }

    public boolean availableTo(Role role) {
        return roles.contains(role);
    }

    public static Optional<ReportCatalogue> byKey(String key) {
        return List.of(values()).stream().filter(r -> r.key.equalsIgnoreCase(key)).findFirst();
    }
}
