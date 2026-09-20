package com.seastella.servicerequest.internal;

import com.seastella.fleet.api.RequestAttachments;
import com.seastella.fleet.api.RequestAttachments.AttachmentRef;
import com.seastella.servicerequest.api.ServiceRequestCommands;
import com.seastella.servicerequest.api.ServiceRequestCommands.Placement;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * The photographs and video a request carries (SoW §6.1, SRQ-02).
 *
 * <p>"The Captain identifies an issue with a Spare and raises a single Service
 * Request directly from its record — Spare, problem description, priority, and
 * supporting photos/video." A photograph of a cracked mount says more than a
 * paragraph describing it, and it is what the Coordinator and the engineer
 * look at first.
 *
 * <p>A file here is a document like any other: same byte check, same storage,
 * same vessel scope, same audit entry. Everyone who can see the request can see
 * its attachments — the Ship Manager approving it, the Coordinator triaging it,
 * the engineer sent to fix it — because the whole point of §6.2 is that the
 * request carries its own context.
 */
@RestController
@RequestMapping("/api/v1/service-requests/{requestId}/attachments")
class RequestAttachmentController {

    private final ServiceRequestCommands requests;
    private final RequestAttachments attachments;

    RequestAttachmentController(ServiceRequestCommands requests, RequestAttachments attachments) {
        this.requests = requests;
        this.attachments = attachments;
    }

    @GetMapping
    ResponseEntity<List<AttachmentView>> list(@PathVariable Long requestId) {
        Placement placement = requests.placementInScope(requestId);      // 404 outside scope
        return ResponseEntity.ok(attachments.forRequest(placement.vesselId(), requestId).stream()
                .map(RequestAttachmentController::view).toList());
    }

    /**
     * Adds a photograph, a video or a document to the request.
     *
     * <p>The Captain who raised it and the Coordinator handling it, and nobody
     * else: the same two people who write in its conversation. The port
     * re-checks both the role and the vessel, so this annotation is the coarse
     * gate rather than the whole rule.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('CAPTAIN','SERVICE_COORDINATOR')")
    ResponseEntity<AttachmentView> upload(@PathVariable Long requestId,
                                          @RequestParam("file") MultipartFile file,
                                          @RequestParam(required = false) String caption) throws IOException {
        Placement placement = requests.placementInScope(requestId);
        byte[] content = file == null ? new byte[0] : file.getBytes();
        AttachmentRef stored = attachments.attach(placement.vesselId(), requestId,
                file == null ? null : file.getOriginalFilename(), content, caption);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(stored));
    }

    private static AttachmentView view(AttachmentRef a) {
        return new AttachmentView(a.documentId(), a.fileName(), a.contentType(), a.sizeBytes(),
                a.title(), a.uploadedBy(), a.uploadedAt(), a.isImage(), a.isVideo());
    }

    record AttachmentView(Long documentId, String fileName, String contentType, long sizeBytes, String title,
                          String uploadedBy, java.time.Instant uploadedAt, boolean image, boolean video) {}
}
