package com.seastella.reporting.internal;

import com.seastella.reporting.api.CaptainDashboard;
import com.seastella.reporting.api.PlatformAdminDashboard;
import com.seastella.reporting.api.ServiceCoordinatorDashboard;
import com.seastella.reporting.api.ServiceEngineerDashboard;
import com.seastella.reporting.api.ShipManagerDashboard;
import com.seastella.reporting.api.TechnicalHeadDashboard;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The six role dashboards.
 *
 * <p>One endpoint per role, each restricted by {@link PreAuthorize} to exactly
 * the role it serves. There is deliberately no generic
 * {@code /api/v1/dashboard?role=...} - a single endpoint that branched on a
 * requested role would put the authorization decision in a request parameter,
 * and parameters are supplied by the caller.
 *
 * <p>Method security here is the coarse check. Each service independently
 * re-confirms the role and resolves the caller's own vessel scope, so nothing
 * is returned on the strength of the URL alone. Scoping happens in SQL, in the
 * metrics layer; no endpoint returns a wider set for the frontend to filter.
 */
@RestController
@RequestMapping("/api/v1/dashboards")
class DashboardController {

    private final PlatformAdminDashboardService platformAdmin;
    private final TechnicalHeadDashboardService technicalHead;
    private final ShipManagerDashboardService shipManager;
    private final CaptainDashboardService captain;
    private final ServiceCoordinatorDashboardService coordinator;
    private final ServiceEngineerDashboardService engineer;

    DashboardController(PlatformAdminDashboardService platformAdmin,
                        TechnicalHeadDashboardService technicalHead,
                        ShipManagerDashboardService shipManager,
                        CaptainDashboardService captain,
                        ServiceCoordinatorDashboardService coordinator,
                        ServiceEngineerDashboardService engineer) {
        this.platformAdmin = platformAdmin;
        this.technicalHead = technicalHead;
        this.shipManager = shipManager;
        this.captain = captain;
        this.coordinator = coordinator;
        this.engineer = engineer;
    }

    /** Platform-wide operations and the consolidated activity feed (SoW s8.5). */
    @GetMapping("/platform-admin")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    ResponseEntity<PlatformAdminDashboard> platformAdmin() {
        return ResponseEntity.ok(platformAdmin.build());
    }

    /** Fleet health across the caller's own organization (SoW s8.1). */
    @GetMapping("/technical-head")
    @PreAuthorize("hasRole('TECHNICAL_HEAD')")
    ResponseEntity<TechnicalHeadDashboard> technicalHead() {
        return ResponseEntity.ok(technicalHead.build());
    }

    /** Only the vessels allocated to this Ship Manager (SoW s8.2). */
    @GetMapping("/ship-manager")
    @PreAuthorize("hasRole('SHIP_MANAGER')")
    ResponseEntity<ShipManagerDashboard> shipManager() {
        return ResponseEntity.ok(shipManager.build());
    }

    /** The Captain's single assigned vessel. Carries no cost data (SoW s12). */
    @GetMapping("/captain")
    @PreAuthorize("hasRole('CAPTAIN')")
    ResponseEntity<CaptainDashboard> captain() {
        return ResponseEntity.ok(captain.build());
    }

    /** The service operations pipeline (SoW s8.4). */
    @GetMapping("/service-coordinator")
    @PreAuthorize("hasRole('SERVICE_COORDINATOR')")
    ResponseEntity<ServiceCoordinatorDashboard> serviceCoordinator() {
        return ResponseEntity.ok(coordinator.build());
    }

    /** Assigned jobs only. Carries no cost data (SoW s12). */
    @GetMapping("/service-engineer")
    @PreAuthorize("hasRole('SERVICE_ENGINEER')")
    ResponseEntity<ServiceEngineerDashboard> serviceEngineer() {
        return ResponseEntity.ok(engineer.build());
    }
}
