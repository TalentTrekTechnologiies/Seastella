package com.seastella.reporting.internal;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A vessel's health numbers, its SBMC certificates and its QM-F11 pre-survey
 * forms. The role on each endpoint is the first gate; the service checks the
 * vessel against the caller's organizations as the second.
 */
@RestController
@RequestMapping("/api/v1")
class VesselDocumentsController {

    private final VesselDocumentsService service;

    VesselDocumentsController(VesselDocumentsService service) {
        this.service = service;
    }

    @GetMapping("/vessels/{vesselId}/health")
    ResponseEntity<VesselDocumentsService.Health> health(@PathVariable Long vesselId) {
        return ResponseEntity.ok(service.health(vesselId));
    }

    // ------------------------------------------------------------------ SBMC

    @GetMapping("/vessels/{vesselId}/sbmc/preview")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    ResponseEntity<VesselDocumentsService.SbmcPreview> previewSbmc(@PathVariable Long vesselId) {
        return ResponseEntity.ok(service.previewSbmc(vesselId));
    }

    @PostMapping("/vessels/{vesselId}/sbmc")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    ResponseEntity<VesselDocumentsService.CertificateView> generateSbmc(
            @PathVariable Long vesselId, @RequestBody VesselDocumentsService.SbmcRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.generateSbmc(vesselId, body));
    }

    @GetMapping("/vessels/{vesselId}/certificates")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','SERVICE_COORDINATOR')")
    ResponseEntity<List<VesselDocumentsService.CertificateView>> certificates(@PathVariable Long vesselId) {
        return ResponseEntity.ok(service.certificates(vesselId));
    }

    @GetMapping("/certificates/{certificateId}/pdf")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','SERVICE_COORDINATOR')")
    ResponseEntity<byte[]> downloadCertificate(@PathVariable Long certificateId) {
        return pdf(service.downloadCertificate(certificateId));
    }

    @PostMapping("/certificates/{certificateId}/revoke")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    ResponseEntity<VesselDocumentsService.CertificateView> revoke(@PathVariable Long certificateId,
                                                                 @RequestBody(required = false) Revoke body) {
        return ResponseEntity.ok(service.revoke(certificateId, body == null ? null : body.reason()));
    }

    // ------------------------------------------------------------ pre-survey

    @GetMapping("/vessels/{vesselId}/pre-survey/draft")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','SERVICE_COORDINATOR')")
    ResponseEntity<VesselDocumentsService.PreSurveyDraft> preSurveyDraft(@PathVariable Long vesselId) {
        return ResponseEntity.ok(service.preSurveyDraft(vesselId));
    }

    @PostMapping("/vessels/{vesselId}/pre-survey")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','SERVICE_COORDINATOR')")
    ResponseEntity<VesselDocumentsService.PreSurveyView> generatePreSurvey(
            @PathVariable Long vesselId, @RequestBody(required = false) VesselDocumentsService.PreSurveyRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.generatePreSurvey(vesselId, body));
    }

    @GetMapping("/vessels/{vesselId}/pre-survey")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','SERVICE_COORDINATOR')")
    ResponseEntity<List<VesselDocumentsService.PreSurveyView>> preSurveyForms(@PathVariable Long vesselId) {
        return ResponseEntity.ok(service.preSurveyForms(vesselId));
    }

    @GetMapping("/pre-survey/{formId}/pdf")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','SERVICE_COORDINATOR')")
    ResponseEntity<byte[]> downloadPreSurvey(@PathVariable Long formId) {
        return pdf(service.downloadPreSurvey(formId));
    }

    private static ResponseEntity<byte[]> pdf(VesselDocumentsService.FileOut file) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .body(file.content());
    }

    record Revoke(String reason) {}
}
