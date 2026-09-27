package com.seastella.reporting.internal;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Three registers from the sidebar. Invoices are for the roles that may see
 * money (SoW s8: never the Captain or the Service Engineer); the engineer list
 * is the Coordinator's, who assigns them; job history is the engineer's own.
 */
@RestController
class WorkRegisterController {

    private final WorkRegisterService registers;

    WorkRegisterController(WorkRegisterService registers) {
        this.registers = registers;
    }

    @GetMapping("/api/v1/invoices")
    @PreAuthorize("hasAnyRole('SHIP_MANAGER','TECHNICAL_HEAD','SERVICE_COORDINATOR','PLATFORM_ADMIN')")
    ResponseEntity<WorkRegisterService.InvoiceRegister> invoices() {
        return ResponseEntity.ok(registers.invoices());
    }

    @GetMapping("/api/v1/engineers/workload")
    @PreAuthorize("hasAnyRole('SERVICE_COORDINATOR','PLATFORM_ADMIN')")
    ResponseEntity<List<WorkRegisterService.EngineerLoad>> engineers() {
        return ResponseEntity.ok(registers.engineers());
    }

    @GetMapping("/api/v1/jobs/history")
    @PreAuthorize("hasRole('SERVICE_ENGINEER')")
    ResponseEntity<List<WorkRegisterService.FinishedJob>> jobHistory() {
        return ResponseEntity.ok(registers.jobHistory());
    }
}
