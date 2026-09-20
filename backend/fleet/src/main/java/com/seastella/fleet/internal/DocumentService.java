package com.seastella.fleet.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.ForbiddenException;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.error.WorkflowException;
import com.seastella.core.api.upload.UploadPolicy;
import com.seastella.fleet.api.DocumentDirectory.DocumentType;
import com.seastella.fleet.api.DocumentDirectory.OwnerType;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.Role;
import com.seastella.identity.api.ScopeGuard;
import com.seastella.identity.api.ScopeResolver;
import com.seastella.identity.api.UserDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Documents and certificates attached to a vessel, a spare or a request
 * (SoW s7, s9.3).
 *
 * <p>Scope first, always: the vessel a document belongs to is checked against
 * the caller's scope before anything is read or written, and a document on
 * another fleet's vessel answers "not found" like any other out-of-scope
 * resource (S-40). Files themselves are validated by their bytes and stored
 * under a name the platform chooses (SEC-15, SEC-16).
 *
 * <p>Nothing is overwritten. Replacing a certificate keeps the old record and
 * links it to the new one, so "what was valid in March" is still answerable
 * (DOC-06); removing marks the row and leaves the file in place.
 */
@Service
class DocumentService {

    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final UploadPolicy uploads;
    private final VesselRepository vessels;
    private final SpareRepository spares;
    private final ScopeGuard scopeGuard;
    private final ScopeResolver scopes;
    private final UserDirectory users;
    private final AuditService audit;

    DocumentService(DocumentRepository documents, DocumentStorage storage, UploadPolicy uploads,
                    VesselRepository vessels, SpareRepository spares, ScopeGuard scopeGuard,
                    ScopeResolver scopes, UserDirectory users, AuditService audit) {
        this.documents = documents;
        this.storage = storage;
        this.uploads = uploads;
        this.vessels = vessels;
        this.spares = spares;
        this.scopeGuard = scopeGuard;
        this.scopes = scopes;
        this.users = users;
        this.audit = audit;
    }

    record NewDocument(OwnerType ownerType, Long ownerId, DocumentType documentType, String title,
                       String certificateNumber, String issuingAuthority, LocalDate issuedDate, LocalDate expiryDate,
                       Long supersedesId) {}

    record DocumentView(Long id, Long vesselId, String vesselName, String ownerType, Long ownerId, String attachedTo,
                        String documentType, String title, String certificateNumber, String issuingAuthority,
                        LocalDate issuedDate, LocalDate expiryDate, Integer daysToExpiry, String fileName,
                        String contentType, long sizeBytes, String uploadedBy, Instant uploadedAt,
                        Long supersededById, boolean current, boolean removed) {}

    record FileContent(byte[] content, String fileName, String contentType) {}

    // -------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    List<DocumentView> list(OwnerType ownerType, Long ownerId, boolean includeHistory) {
        Long vesselId = vesselOf(ownerType, ownerId);
        scopeGuard.assertVessel(vesselId);
        return view(documents.findByOwnerTypeAndOwnerIdOrderByIdDesc(ownerType, ownerId).stream()
                .filter(d -> includeHistory || d.isCurrent())
                .toList());
    }

    /** Everything on one vessel, whatever it is attached to. */
    @Transactional(readOnly = true)
    List<DocumentView> forVessel(Long vesselId, boolean includeHistory) {
        scopeGuard.assertVessel(vesselId);
        return view(documents.findByVesselIdOrderByIdDesc(vesselId).stream()
                .filter(d -> includeHistory || d.isCurrent())
                .toList());
    }

    /** The file itself. Scope is re-checked here, not only when listing (S-40). */
    @Transactional(readOnly = true)
    FileContent content(Long documentId) {
        Document document = load(documentId);
        return new FileContent(storage.read(document.getStorageKey()), document.getFileName(),
                document.getContentType());
    }

    // ------------------------------------------------------------------- write

    @Transactional
    DocumentView upload(NewDocument command, String fileName, byte[] content) {
        AccessScope actor = scopes.currentScope();
        if (command == null || command.ownerType() == null || command.ownerId() == null) {
            throw new ValidationException("Say what this document belongs to.");
        }
        Long vesselId = vesselOf(command.ownerType(), command.ownerId());
        scopeGuard.assertVessel(vesselId);
        assertMayAttach(actor, command.ownerType());

        DocumentType type = command.documentType() == null ? DocumentType.OTHER : command.documentType();
        String title = required(command.title(), 200);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (type == DocumentType.CERTIFICATE) {
            if (command.expiryDate() == null) {
                throw new ValidationException("A certificate needs its expiry date, so SeaStella can warn you before it runs out.");
            }
            if (command.issuedDate() != null && command.issuedDate().isAfter(today)) {
                throw new ValidationException("The issue date cannot be in the future.");
            }
            if (command.issuedDate() != null && command.expiryDate().isBefore(command.issuedDate())) {
                throw new ValidationException("The expiry date is before the issue date.");
            }
        }

        Document superseded = command.supersedesId() == null ? null : load(command.supersedesId());
        if (superseded != null) {
            if (!superseded.isCurrent()) {
                throw new WorkflowException("That document has already been replaced or removed.");
            }
            if (!Objects.equals(superseded.getOwnerId(), command.ownerId())
                    || superseded.getOwnerType() != command.ownerType()) {
                throw new ValidationException("A replacement must be attached to the same thing as the document it replaces.");
            }
        }

        UploadPolicy.Checked checked = uploads.check(fileName, content);
        String storageKey = storage.write(content);
        try {
            Long organizationId = vessels.findById(vesselId).map(Vessel::getOrganizationId).orElseThrow();
            Document document = new Document(organizationId, vesselId, command.ownerType(), command.ownerId(),
                    type, title, checked.fileName(), checked.contentType(), checked.sizeBytes(), storageKey,
                    checked.sha256(), actor.userId(), Instant.now());
            if (type == DocumentType.CERTIFICATE) {
                document.describeCertificate(text(command.certificateNumber(), 80), text(command.issuingAuthority(), 160),
                        command.issuedDate(), command.expiryDate());
            }
            Document saved = documents.save(document);
            if (superseded != null) {
                superseded.supersededBy(saved.getId());
                documents.save(superseded);
            }

            audit.record(entry(actor, AuditAction.DOCUMENT_UPLOADED, saved)
                    .after(AuditJson.of("title", title, "type", type.name(), "attachedTo",
                            command.ownerType().name() + " " + command.ownerId(), "fileName", checked.fileName(),
                            "sizeBytes", checked.sizeBytes(), "sha256", checked.sha256(),
                            "expiryDate", command.expiryDate(), "replaces", command.supersedesId()))
                    .build());
            return view(List.of(saved)).get(0);
        } catch (RuntimeException e) {
            // The row did not save: do not leave the file behind.
            storage.deleteQuietly(storageKey);
            throw e;
        }
    }

    /**
     * Stores a file against a service request (CHT-08).
     *
     * <p>Fleet cannot look a request up, so the vessel comes from the caller -
     * and is then checked against the caller's own scope here, not taken on
     * trust. Only the two people who write in the chat may attach to it.
     */
    @Transactional
    DocumentView attachToRequest(Long vesselId, Long serviceRequestId, String fileName, byte[] content, String caption) {
        AccessScope actor = scopes.currentScope();
        if (vesselId == null || serviceRequestId == null) {
            throw new ValidationException("Say what this file belongs to.");
        }
        scopeGuard.assertVessel(vesselId);
        if (actor.role() != Role.CAPTAIN && actor.role() != Role.SERVICE_COORDINATOR) {
            throw ForbiddenException.ofAction("attach a file to this request");
        }

        UploadPolicy.Checked checked = uploads.check(fileName, content);
        String title = caption == null || caption.isBlank() ? checked.fileName() : text(caption, 200);
        DocumentType type = checked.contentType().startsWith("image/") || checked.contentType().startsWith("video/")
                ? DocumentType.PHOTO
                : DocumentType.OTHER;

        String storageKey = storage.write(content);
        try {
            Long organizationId = vessels.findById(vesselId).map(Vessel::getOrganizationId).orElseThrow();
            Document document = documents.save(new Document(organizationId, vesselId, OwnerType.SERVICE_REQUEST,
                    serviceRequestId, type, title, checked.fileName(), checked.contentType(), checked.sizeBytes(),
                    storageKey, checked.sha256(), actor.userId(), Instant.now()));
            audit.record(entry(actor, AuditAction.DOCUMENT_UPLOADED, document)
                    .after(AuditJson.of("title", title, "type", type.name(), "attachedTo",
                            "SERVICE_REQUEST " + serviceRequestId, "fileName", checked.fileName(),
                            "sizeBytes", checked.sizeBytes(), "sha256", checked.sha256()))
                    .build());
            return view(List.of(document)).get(0);
        } catch (RuntimeException e) {
            storage.deleteQuietly(storageKey);
            throw e;
        }
    }

    /** Everything filed against one request, newest first (SoW §6.1, CHT-08). */
    @Transactional(readOnly = true)
    List<DocumentView> forRequest(Long vesselId, Long serviceRequestId) {
        scopeGuard.assertVessel(vesselId);
        return view(documents.findByOwnerTypeAndOwnerIdOrderByIdDesc(OwnerType.SERVICE_REQUEST, serviceRequestId)
                .stream()
                .filter(Document::isCurrent)
                // Belt and braces: the owner id alone would be enough, but a
                // request id is not unique across fleets in the caller's head.
                .filter(d -> Objects.equals(d.getVesselId(), vesselId))
                .toList());
    }

    /** The records behind message attachments, for the transcript. */
    @Transactional(readOnly = true)
    List<DocumentView> byIds(List<Long> documentIds) {
        if (documentIds.isEmpty()) return List.of();
        return view(documents.findAllById(documentIds).stream()
                .filter(d -> scopes.currentScope().permitsVessel(d.getVesselId()))
                .toList());
    }

    /** Marks a document removed. The file and the record stay, so the trail is intact. */
    @Transactional
    void remove(Long documentId, String reason) {
        AccessScope actor = scopes.currentScope();
        Document document = load(documentId);
        assertMayAttach(actor, document.getOwnerType());
        if (document.getRemovedAt() != null) {
            throw new WorkflowException("That document has already been removed.");
        }
        document.removed(actor.userId(), Instant.now());
        documents.save(document);
        audit.record(entry(actor, AuditAction.DOCUMENT_DELETED, document)
                .before(AuditJson.of("title", document.getTitle(), "fileName", document.getFileName()))
                .after(AuditJson.of("reason", text(reason, 500))).build());
    }

    // --------------------------------------------------------------- internals

    /**
     * Who may attach or remove. Reading is governed by vessel scope alone - the
     * Captain and the engineer on the job both need the manual - but writing
     * follows the same shape as the rest of master data.
     */
    private static void assertMayAttach(AccessScope actor, OwnerType ownerType) {
        boolean allowed = switch (actor.role()) {
            // Master data is theirs: certificates, manuals, anything on the fleet.
            case PLATFORM_ADMIN, TECHNICAL_HEAD, SHIP_MANAGER -> true;
            // A Captain photographs and files what is on their own equipment.
            case CAPTAIN -> ownerType == OwnerType.SPARE;
            default -> false;
        };
        if (!allowed) {
            throw ForbiddenException.ofAction("attach documents here");
        }
    }

    private Document load(Long documentId) {
        Document document = documents.findById(documentId)
                .orElseThrow(() -> NotFoundException.ofResource("Document", documentId));
        scopeGuard.assertVessel(document.getVesselId());
        return document;
    }

    /** A document is always on a vessel; the owner says what on it. */
    private Long vesselOf(OwnerType ownerType, Long ownerId) {
        return switch (ownerType) {
            case VESSEL -> vessels.findById(ownerId).map(Vessel::getId)
                    .orElseThrow(() -> NotFoundException.ofResource("Vessel", ownerId));
            case SPARE -> spares.findById(ownerId).map(Spare::getVesselId)
                    .orElseThrow(() -> NotFoundException.ofResource("Spare", ownerId));
            // Chat attachments reach this table through attachToRequest, which
            // is given the vessel: fleet cannot look a request up from here.
            case SERVICE_REQUEST -> throw new ValidationException(
                    "Documents are attached to a vessel or to a piece of equipment.");
        };
    }

    private List<DocumentView> view(List<Document> found) {
        Map<Long, String> vesselNames = vessels.findAllById(found.stream().map(Document::getVesselId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(Vessel::getId, Vessel::getName));
        Map<Long, String> spareNames = spares.findAllById(found.stream()
                        .filter(d -> d.getOwnerType() == OwnerType.SPARE).map(Document::getOwnerId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(Spare::getId, Spare::getName));
        Map<Long, UserDirectory.UserRef> people = users.findAll(
                found.stream().map(Document::getUploadedByUserId).distinct().toList());
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        return found.stream().map(d -> new DocumentView(
                d.getId(), d.getVesselId(), vesselNames.get(d.getVesselId()), d.getOwnerType().name(), d.getOwnerId(),
                attachedTo(d, spareNames, vesselNames),
                d.getDocumentType().name(), d.getTitle(), d.getCertificateNumber(), d.getIssuingAuthority(),
                d.getIssuedDate(), d.getExpiryDate(),
                d.getExpiryDate() == null ? null : (int) ChronoUnit.DAYS.between(today, d.getExpiryDate()),
                d.getFileName(), d.getContentType(), d.getSizeBytes(),
                people.containsKey(d.getUploadedByUserId()) ? people.get(d.getUploadedByUserId()).fullName() : null,
                d.getUploadedAt(), d.getSupersededById(), d.isCurrent(), d.getRemovedAt() != null)).toList();
    }

    /** What the document is filed against, in words, for a list to read sensibly. */
    private static String attachedTo(Document d, Map<Long, String> spareNames, Map<Long, String> vesselNames) {
        return switch (d.getOwnerType()) {
            case SPARE -> spareNames.get(d.getOwnerId());
            case SERVICE_REQUEST -> "Service request";
            case VESSEL -> vesselNames.get(d.getVesselId());
        };
    }

    private static AuditEntry.Builder entry(AccessScope actor, String action, Document document) {
        return AuditEntry.builder()
                .actor(actor.userId(), actor.role().name())
                .action(action)
                .entity("Document", document.getId())
                .scope(document.getOrganizationId(), document.getVesselId());
    }

    private static String required(String value, int max) {
        String t = value == null ? "" : value.trim();
        if (t.isEmpty()) throw new ValidationException("Give the document a title.");
        if (t.length() > max) throw new ValidationException("Keep the title under " + max + " characters.");
        return t;
    }

    private static String text(String value, int max) {
        if (value == null) return null;
        String t = value.trim();
        if (t.isEmpty()) return null;
        if (t.length() > max) throw new ValidationException("Keep this under " + max + " characters.");
        return t;
    }
}
