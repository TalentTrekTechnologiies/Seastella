package com.seastella.fleet.internal;

import com.seastella.fleet.api.DocumentDirectory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Reads for other modules: expiry alerts (NOT-11) and the certificate report (RPT-05). */
@Component
class DefaultDocumentDirectory implements DocumentDirectory {

    private final DocumentRepository documents;
    private final VesselRepository vessels;
    private final SpareRepository spares;

    DefaultDocumentDirectory(DocumentRepository documents, VesselRepository vessels, SpareRepository spares) {
        this.documents = documents;
        this.vessels = vessels;
        this.spares = spares;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CertificateRef> certificatesExpiringBy(LocalDate date) {
        return refs(documents.certificatesExpiringBy(DocumentType.CERTIFICATE, date));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CertificateRef> certificatesForVessels(List<Long> vesselIds) {
        if (vesselIds == null || vesselIds.isEmpty()) return List.of();
        return refs(documents.certificatesForVessels(DocumentType.CERTIFICATE, vesselIds));
    }

    private List<CertificateRef> refs(List<Document> found) {
        Map<Long, String> vesselNames = vessels.findAllById(found.stream().map(Document::getVesselId).distinct().toList())
                .stream().collect(Collectors.toMap(Vessel::getId, Vessel::getName));
        Map<Long, String> spareNames = spares.findAllById(found.stream()
                        .filter(d -> d.getOwnerType() == OwnerType.SPARE).map(Document::getOwnerId).distinct().toList())
                .stream().collect(Collectors.toMap(Spare::getId, Spare::getName));

        return found.stream().map(d -> new CertificateRef(d.getId(), d.getOrganizationId(), d.getVesselId(),
                vesselNames.get(d.getVesselId()), d.getOwnerType(), d.getOwnerId(),
                d.getOwnerType() == OwnerType.SPARE ? spareNames.get(d.getOwnerId()) : vesselNames.get(d.getVesselId()),
                d.getTitle(), d.getCertificateNumber(), d.getIssuingAuthority(), d.getIssuedDate(),
                d.getExpiryDate())).toList();
    }
}
