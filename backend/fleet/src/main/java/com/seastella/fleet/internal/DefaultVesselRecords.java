package com.seastella.fleet.internal;

import com.seastella.core.api.audit.AuditAction;
import com.seastella.core.api.audit.AuditEntry;
import com.seastella.core.api.audit.AuditJson;
import com.seastella.core.api.audit.AuditService;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.error.ValidationException;
import com.seastella.core.api.upload.UploadPolicy;
import com.seastella.fleet.api.DocumentDirectory.DocumentType;
import com.seastella.fleet.api.DocumentDirectory.OwnerType;
import com.seastella.fleet.api.VesselRecords;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.ScopeGuard;
import com.seastella.identity.api.ScopeResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
class DefaultVesselRecords implements VesselRecords {

    private final VesselRepository vessels;
    private final OrganizationRepository organizations;
    private final SpareRepository spares;
    private final SpareServiceRecordRepository history;
    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final UploadPolicy uploads;
    private final ScopeGuard scopeGuard;
    private final ScopeResolver scopes;
    private final AuditService audit;

    DefaultVesselRecords(VesselRepository vessels, OrganizationRepository organizations, SpareRepository spares,
                         SpareServiceRecordRepository history, DocumentRepository documents, DocumentStorage storage,
                         UploadPolicy uploads, ScopeGuard scopeGuard, ScopeResolver scopes, AuditService audit) {
        this.vessels = vessels;
        this.organizations = organizations;
        this.spares = spares;
        this.history = history;
        this.documents = documents;
        this.storage = storage;
        this.uploads = uploads;
        this.scopeGuard = scopeGuard;
        this.scopes = scopes;
        this.audit = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VesselParticulars> particulars(Long vesselId) {
        if (vesselId == null) return Optional.empty();
        scopeGuard.assertVessel(vesselId);
        return vessels.findById(vesselId).map(v -> new VesselParticulars(v.getId(), v.getOrganizationId(),
                organizations.findById(v.getOrganizationId()).map(Organization::getName).orElse(null),
                v.getName(), v.getImoNumber(), v.getMmsi(), v.getCallSign(), v.getFlag(), v.getVesselClass(),
                v.getArea(), v.getVesselType(), v.getDwt(), v.getStatus().name(), v.getCreatedAt(),
                v.getUpdatedAt(), v.getUpdatedBy()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<EquipmentRecord> register(Long vesselId) {
        scopeGuard.assertVessel(vesselId);
        // The newest replacement of each kind per spare, from one read of the vessel's history.
        Map<Long, LocalDate> replaced = new HashMap<>();
        Map<Long, LocalDate> sphere = new HashMap<>();
        for (SpareServiceRecord r : history.findByVesselIdInOrderByServiceDateDescIdDesc(java.util.Set.of(vesselId),
                org.springframework.data.domain.Pageable.unpaged())) {
            if (SpareServiceRecord.REPLACEMENT.equals(r.getKind())) replaced.putIfAbsent(r.getSpareId(), r.getServiceDate());
            if (SpareServiceRecord.SPHERE_REPLACEMENT.equals(r.getKind())) sphere.putIfAbsent(r.getSpareId(), r.getServiceDate());
        }
        return spares.inVmpOrder(vesselId).stream()
                .map(s -> new EquipmentRecord(s.getId(), s.getPath(), s.getName(), s.getMake(), s.getModel(),
                        s.getSerialNumber(), s.getExpirationDate(), s.getLastAnnualServiceDate(), s.getRunningHours(),
                        replaced.get(s.getId()), sphere.get(s.getId())))
                .toList();
    }

    @Override
    @Transactional
    public Long file(GeneratedFile file) {
        if (file == null || file.vesselId() == null) throw new ValidationException("Say which vessel this is for.");
        scopeGuard.assertVessel(file.vesselId());
        AccessScope actor = scopes.currentScope();
        Vessel vessel = vessels.findById(file.vesselId())
                .orElseThrow(() -> NotFoundException.ofResource("Vessel", file.vesselId()));
        Document replaced = file.supersedesDocumentId() == null ? null
                : documents.findById(file.supersedesDocumentId())
                .filter(d -> d.getVesselId().equals(vessel.getId()) && d.isCurrent())
                .orElse(null);

        UploadPolicy.Checked checked = uploads.check(file.fileName(), file.content());
        String storageKey = storage.write(file.content());
        try {
            Document document = new Document(vessel.getOrganizationId(), vessel.getId(), OwnerType.VESSEL,
                    vessel.getId(), file.type(), file.title(), checked.fileName(), checked.contentType(),
                    checked.sizeBytes(), storageKey, checked.sha256(), actor.userId(), Instant.now());
            if (file.type() == DocumentType.CERTIFICATE) {
                document.describeCertificate(file.certificateNumber(), file.issuingAuthority(),
                        file.issuedDate(), file.expiryDate());
            }
            document.markIssuedByPlatform();
            Document saved = documents.save(document);
            if (replaced != null) {
                replaced.supersededBy(saved.getId());
                documents.save(replaced);
            }
            audit.record(AuditEntry.builder()
                    .actor(actor.userId(), actor.role().name())
                    .action(AuditAction.DOCUMENT_UPLOADED)
                    .entity("Document", saved.getId())
                    .scope(vessel.getOrganizationId(), vessel.getId())
                    .after(AuditJson.of("title", file.title(), "type", file.type().name(), "generated", true,
                            "fileName", checked.fileName(), "sizeBytes", checked.sizeBytes(),
                            "sha256", checked.sha256(), "certificateNumber", file.certificateNumber(),
                            "expiryDate", file.expiryDate(), "replaces", replaced == null ? null : replaced.getId()))
                    .build());
            return saved.getId();
        } catch (RuntimeException e) {
            storage.deleteQuietly(storageKey);
            throw e;
        }
    }

    @Override
    @Transactional
    public void withdraw(Long documentId, String reason) {
        Document document = load(documentId);
        if (document.getRemovedAt() != null) return;
        AccessScope actor = scopes.currentScope();
        document.removed(actor.userId(), Instant.now());
        documents.save(document);
        audit.record(AuditEntry.builder()
                .actor(actor.userId(), actor.role().name())
                .action(AuditAction.DOCUMENT_DELETED)
                .entity("Document", document.getId())
                .scope(document.getOrganizationId(), document.getVesselId())
                .before(AuditJson.of("title", document.getTitle(), "fileName", document.getFileName()))
                .after(AuditJson.of("reason", reason))
                .build());
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] read(Long documentId) {
        return storage.read(load(documentId).getStorageKey());
    }

    private Document load(Long documentId) {
        Document document = documents.findById(documentId)
                .orElseThrow(() -> NotFoundException.ofResource("Document", documentId));
        scopeGuard.assertVessel(document.getVesselId());
        return document;
    }
}
