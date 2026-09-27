package com.seastella.servicerequest.internal;

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
import java.time.Instant;

/**
 * The job log on a request. Read by anyone who can see the request; written by
 * the engineer on the job, as a form so a photograph can go with an entry.
 */
@RestController
@RequestMapping("/api/v1/service-requests/{requestId}/job-log")
class JobLogController {

    private final JobLogService log;

    JobLogController(JobLogService log) {
        this.log = log;
    }

    @GetMapping
    ResponseEntity<JobLogService.JobLog> view(@PathVariable Long requestId) {
        return ResponseEntity.ok(log.view(requestId));
    }

    @PostMapping
    @PreAuthorize("hasRole('SERVICE_ENGINEER')")
    ResponseEntity<JobLogService.EntryView> add(@PathVariable Long requestId,
                                                @RequestParam String kind,
                                                @RequestParam(required = false) Instant occurredAt,
                                                @RequestParam(required = false) String note,
                                                @RequestParam(value = "file", required = false) MultipartFile file)
            throws IOException {
        byte[] content = file == null || file.isEmpty() ? null : file.getBytes();
        return ResponseEntity.status(HttpStatus.CREATED).body(log.add(requestId, kind, occurredAt, note,
                file == null ? null : file.getOriginalFilename(), content));
    }
}
