package com.seastella.troubleshooting.internal;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Guided checks on a service request.
 *
 * <p>Reading the log needs only the request in scope (404 otherwise). Running
 * the checks is the Captain's. Each write commits before the view is read back,
 * so the response always shows the request's settled state.
 */
@RestController
@RequestMapping("/api/v1/service-requests/{requestId}/troubleshooting")
class TroubleshootingController {

    private final TroubleshootingService service;

    TroubleshootingController(TroubleshootingService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<TroubleshootingService.View> view(@PathVariable Long requestId) {
        return ResponseEntity.ok(service.view(requestId));
    }

    @PostMapping
    @PreAuthorize("hasRole('CAPTAIN')")
    ResponseEntity<TroubleshootingService.View> start(@PathVariable Long requestId, @RequestBody(required = false) StartBody body) {
        service.start(requestId, body == null ? null : body.problemTypeId());
        return ResponseEntity.ok(service.view(requestId));
    }

    @PostMapping("/answers")
    @PreAuthorize("hasRole('CAPTAIN')")
    ResponseEntity<TroubleshootingService.View> answer(@PathVariable Long requestId, @RequestBody AnswerBody body) {
        service.answer(requestId, body == null ? null : body.stepId(), body == null ? null : body.yes(),
                body == null ? null : body.note());
        return ResponseEntity.ok(service.view(requestId));
    }

    @PostMapping("/completion")
    @PreAuthorize("hasRole('CAPTAIN')")
    ResponseEntity<TroubleshootingService.View> complete(@PathVariable Long requestId, @RequestBody CompleteBody body) {
        service.complete(requestId, body == null ? null : body.rootCauseNote(), body == null ? null : body.temporaryFixNote());
        return ResponseEntity.ok(service.view(requestId));
    }

    record StartBody(Long problemTypeId) {}

    record AnswerBody(Long stepId, Boolean yes, String note) {}

    record CompleteBody(String rootCauseNote, String temporaryFixNote) {}
}
