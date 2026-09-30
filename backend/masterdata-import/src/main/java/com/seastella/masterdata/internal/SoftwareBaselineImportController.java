package com.seastella.masterdata.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.fleet.api.SoftwareBaselineGateway;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.ScopeResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Uploading the software master sheet (SoW s9.3).
 *
 * <p>One file, one write. Unlike the equipment import this does not stage a
 * batch for review: a baseline row is four cells with no relationships to
 * resolve, nothing is created on a vessel, and every row is reported back with
 * what it did - so the confirmation happens on the result rather than before
 * it. A wrong version is corrected by re-uploading or by editing the row.
 *
 * <p>Platform Admin only. These rows decide what every fleet's equipment list
 * reports as out of date.
 */
@RestController
@RequestMapping("/api/v1/imports/software-baselines")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class SoftwareBaselineImportController {

    private final SoftwareBaselineGateway baselines;
    private final AuditService audit;
    private final ScopeResolver scopes;

    SoftwareBaselineImportController(SoftwareBaselineGateway baselines, AuditService audit, ScopeResolver scopes) {
        this.baselines = baselines;
        this.audit = audit;
        this.scopes = scopes;
    }

    @PostMapping
    ResponseEntity<UploadResult> upload(@RequestParam("file") MultipartFile file) throws IOException {
        byte[] content = file == null ? new byte[0] : file.getBytes();
        SoftwareBaselineSheet.Parsed parsed = SoftwareBaselineSheet.read(content);

        SoftwareBaselineGateway.UpsertResult written = baselines.upsertFromSheet(parsed.rows());

        AccessScope scope = scopes.currentScope();
        audit.record(AuditEntry.builder()
                .actor(scope.userId(), scope.role().name())
                .action(AuditAction.SOFTWARE_BASELINE_IMPORTED)
                .entity("SoftwareBaseline", null)
                .after(AuditJson.of(
                        "file", file == null ? null : file.getOriginalFilename(),
                        "rows", parsed.rows().size(),
                        "added", written.added(),
                        "updated", written.updated(),
                        "unchanged", written.unchanged(),
                        "skipped", written.skipped().size()))
                .build());

        List<String> skipped = written.skipped().stream()
                .map(s -> s.make() + " " + s.model() + " — " + s.reason())
                .toList();

        return ResponseEntity.ok(new UploadResult(
                parsed.rows().size(), written.added(), written.updated(), written.unchanged(),
                skipped, parsed.warnings()));
    }

    /**
     * @param rowsRead  rows the sheet yielded
     * @param skipped   rows the write declined, with the reason
     * @param warnings  rows the reader could not use at all
     */
    record UploadResult(int rowsRead, int added, int updated, int unchanged,
                        List<String> skipped, List<String> warnings) {}
}
