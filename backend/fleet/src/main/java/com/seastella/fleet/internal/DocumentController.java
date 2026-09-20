package com.seastella.fleet.internal;

import com.seastella.core.api.error.ValidationException;
import com.seastella.fleet.api.DocumentDirectory.DocumentType;
import com.seastella.fleet.api.DocumentDirectory.OwnerType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Documents and certificates (SoW s7; DOC-01 to DOC-07).
 *
 * <p>Uploads carry their metadata as form fields beside the file. Downloads are
 * always an attachment, never rendered: a stored file is data, and a browser
 * that decides to run it would turn an upload endpoint into a way to serve
 * script from this origin (SEC-16).
 */
@RestController
@RequestMapping("/api/v1/documents")
class DocumentController {

    private final DocumentService documents;

    DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping
    ResponseEntity<List<DocumentService.DocumentView>> list(@RequestParam OwnerType ownerType,
                                                            @RequestParam Long ownerId,
                                                            @RequestParam(defaultValue = "false") boolean history) {
        return ResponseEntity.ok(documents.list(ownerType, ownerId, history));
    }

    /** Everything filed on one vessel, whatever it is attached to. */
    @GetMapping("/vessel/{vesselId}")
    ResponseEntity<List<DocumentService.DocumentView>> forVessel(@PathVariable Long vesselId,
                                                                 @RequestParam(defaultValue = "false") boolean history) {
        return ResponseEntity.ok(documents.forVessel(vesselId, history));
    }

    @PostMapping
    ResponseEntity<DocumentService.DocumentView> upload(@RequestParam("file") MultipartFile file,
                                                        @RequestParam OwnerType ownerType,
                                                        @RequestParam Long ownerId,
                                                        @RequestParam(required = false) DocumentType documentType,
                                                        @RequestParam String title,
                                                        @RequestParam(required = false) String certificateNumber,
                                                        @RequestParam(required = false) String issuingAuthority,
                                                        @RequestParam(required = false) String issuedDate,
                                                        @RequestParam(required = false) String expiryDate,
                                                        @RequestParam(required = false) Long supersedesId)
            throws IOException {
        DocumentService.NewDocument command = new DocumentService.NewDocument(ownerType, ownerId, documentType, title,
                certificateNumber, issuingAuthority, date(issuedDate, "issue date"), date(expiryDate, "expiry date"),
                supersedesId);
        byte[] content = file == null ? new byte[0] : file.getBytes();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documents.upload(command, file == null ? null : file.getOriginalFilename(), content));
    }

    @GetMapping("/{documentId}/content")
    ResponseEntity<byte[]> content(@PathVariable Long documentId) {
        DocumentService.FileContent file = documents.content(documentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                // Never let a browser guess a type and run what it finds.
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=0, no-store")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    @DeleteMapping("/{documentId}")
    ResponseEntity<Void> remove(@PathVariable Long documentId,
                                @RequestParam(required = false) String reason) {
        documents.remove(documentId, reason);
        return ResponseEntity.noContent().build();
    }

    private static LocalDate date(String raw, String what) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new ValidationException("Enter the " + what + " as a date.");
        }
    }
}
