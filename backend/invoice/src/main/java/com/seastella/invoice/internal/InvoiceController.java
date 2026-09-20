package com.seastella.invoice.internal;

import com.seastella.core.api.error.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Invoice actions. Each returns the ids the client needs to re-read the
 * request, whose detail already carries the invoice list and next actions.
 *
 * <p>Raising belongs to the Service Coordinator; deciding to the Ship Manager
 * (RBAC matrix). Method security narrows the door; the state machine still
 * decides whether the step is legal for this particular request.
 */
@RestController
class InvoiceController {

    private final InvoiceCommandService service;

    InvoiceController(InvoiceCommandService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/service-requests/{requestId}/invoices")
    @PreAuthorize("hasRole('SERVICE_COORDINATOR')")
    ResponseEntity<Result> raise(@PathVariable Long requestId, @RequestBody RaiseBody body) {
        Long invoiceId = service.raise(requestId, body.amount(), body.currency(), body.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(new Result(invoiceId, requestId));
    }

    @PostMapping("/api/v1/invoices/{invoiceId}/decision")
    @PreAuthorize("hasRole('SHIP_MANAGER')")
    ResponseEntity<Result> decide(@PathVariable Long invoiceId, @RequestBody DecisionBody body) {
        InvoiceCommandService.Decision decision;
        try {
            decision = InvoiceCommandService.Decision.valueOf(body.decision().trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new ValidationException("Decision must be ACCEPT, REJECT or QUERY.");
        }
        Long requestId = service.decide(invoiceId, decision, body.note());
        return ResponseEntity.ok(new Result(invoiceId, requestId));
    }

    record RaiseBody(BigDecimal amount, String currency, String description) {}

    record DecisionBody(String decision, String note) {}

    record Result(Long invoiceId, Long serviceRequestId) {}
}
