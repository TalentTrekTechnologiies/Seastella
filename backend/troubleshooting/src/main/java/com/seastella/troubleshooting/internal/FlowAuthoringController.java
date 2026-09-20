package com.seastella.troubleshooting.internal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Guided-check authoring for the Platform Admin (RBAC: "Author troubleshooting
 * content", Platform Admin only). Each write commits before the flow is read
 * back, so the response carries the version the next save must quote.
 */
@RestController
@RequestMapping("/api/v1/troubleshooting/flows")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class FlowAuthoringController {

    private final FlowAuthoringService authoring;

    FlowAuthoringController(FlowAuthoringService authoring) {
        this.authoring = authoring;
    }

    @GetMapping
    ResponseEntity<List<FlowAuthoringService.FlowSummary>> list() {
        return ResponseEntity.ok(authoring.list());
    }

    @PostMapping
    ResponseEntity<FlowAuthoringService.FlowDetail> create(@RequestBody(required = false) FlowAuthoringService.CreateInput body) {
        Long id = authoring.create(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(authoring.detail(id));
    }

    @GetMapping("/{flowId}")
    ResponseEntity<FlowAuthoringService.FlowDetail> detail(@PathVariable Long flowId) {
        return ResponseEntity.ok(authoring.detail(flowId));
    }

    @PutMapping("/{flowId}")
    ResponseEntity<FlowAuthoringService.FlowDetail> save(@PathVariable Long flowId,
                                                         @RequestBody(required = false) FlowAuthoringService.DraftInput body) {
        authoring.saveDraft(flowId, body);
        return ResponseEntity.ok(authoring.detail(flowId));
    }

    /** Starts the next version from this one (or returns the draft already open). */
    @PostMapping("/{flowId}/drafts")
    ResponseEntity<FlowAuthoringService.FlowDetail> newDraft(@PathVariable Long flowId) {
        return ResponseEntity.ok(authoring.detail(authoring.newDraft(flowId)));
    }

    @PostMapping("/{flowId}/publication")
    ResponseEntity<FlowAuthoringService.FlowDetail> publish(@PathVariable Long flowId,
                                                            @RequestBody(required = false) VersionBody body) {
        authoring.publish(flowId, body == null ? null : body.version());
        return ResponseEntity.ok(authoring.detail(flowId));
    }

    @PostMapping("/{flowId}/retirement")
    ResponseEntity<FlowAuthoringService.FlowDetail> retire(@PathVariable Long flowId) {
        authoring.retire(flowId);
        return ResponseEntity.ok(authoring.detail(flowId));
    }

    @DeleteMapping("/{flowId}")
    ResponseEntity<Void> discard(@PathVariable Long flowId) {
        authoring.discard(flowId);
        return ResponseEntity.noContent().build();
    }

    record VersionBody(Long version) {}
}
