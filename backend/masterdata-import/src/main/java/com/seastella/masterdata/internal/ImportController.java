package com.seastella.masterdata.internal;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * VMP master-data import (SoW s10; RBAC: Platform Admin, and a Technical Head
 * for their own organization).
 *
 * <p>Upload stages and previews; a separate confirmation commits. The two are
 * deliberately separate calls, because "no commit without explicit
 * confirmation" (IMP-08) is the whole safeguard of this feature.
 */
@RestController
@RequestMapping("/api/v1/imports")
@PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TECHNICAL_HEAD')")
class ImportController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ImportService imports;

    ImportController(ImportService imports) {
        this.imports = imports;
    }

    /** The blank template, or one vessel's current spares to edit and send back. */
    @GetMapping("/template")
    ResponseEntity<byte[]> template(@RequestParam(required = false) Long vesselId) {
        byte[] workbook = imports.template(vesselId);
        String name = vesselId == null ? "seastella-spares-template.xlsx" : "seastella-spares-" + vesselId + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(MediaType.parseMediaType(XLSX))
                .body(workbook);
    }

    @PostMapping
    ResponseEntity<ImportService.BatchView> upload(@RequestParam("file") MultipartFile file) throws IOException {
        byte[] content = file == null ? new byte[0] : file.getBytes();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(imports.upload(safeName(file == null ? null : file.getOriginalFilename()), content));
    }

    @GetMapping
    ResponseEntity<List<ImportService.BatchView>> history(@RequestParam(defaultValue = "25") int limit) {
        return ResponseEntity.ok(imports.history(limit));
    }

    @GetMapping("/{batchId}")
    ResponseEntity<ImportService.BatchView> detail(@PathVariable Long batchId) {
        return ResponseEntity.ok(imports.detail(batchId));
    }

    @PostMapping("/{batchId}/commit")
    ResponseEntity<ImportService.BatchView> commit(@PathVariable Long batchId) {
        imports.commit(batchId);
        return ResponseEntity.ok(imports.detail(batchId));
    }

    @PostMapping("/{batchId}/discard")
    ResponseEntity<ImportService.BatchView> discard(@PathVariable Long batchId) {
        imports.discard(batchId);
        return ResponseEntity.ok(imports.detail(batchId));
    }

    /** Filenames reach the log and the history list; keep them plain, and keep any path out. */
    static String safeName(String raw) {
        if (raw == null) return "upload.xlsx";
        String name = raw.replaceAll("[\\\\/\\r\\n\\t]", " ").trim();
        return name.isEmpty() ? "upload.xlsx" : name;
    }
}
