package com.seastella.fleet.internal;

import com.seastella.fleet.api.RequestAttachments;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Adapts {@link DocumentService} to the request-attachment port (CHT-08). */
@Component
class DefaultRequestAttachments implements RequestAttachments {

    private final DocumentService documents;

    DefaultRequestAttachments(DocumentService documents) {
        this.documents = documents;
    }

    @Override
    @Transactional
    public AttachmentRef attach(Long vesselId, Long serviceRequestId, String fileName, byte[] content, String caption) {
        return ref(documents.attachToRequest(vesselId, serviceRequestId, fileName, content, caption));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, AttachmentRef> describe(Collection<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) return Map.of();
        List<Long> ids = documentIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        return documents.byIds(ids).stream()
                .map(DefaultRequestAttachments::ref)
                .collect(java.util.stream.Collectors.toMap(AttachmentRef::documentId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttachmentRef> forRequest(Long vesselId, Long serviceRequestId) {
        return documents.forRequest(vesselId, serviceRequestId).stream()
                .map(DefaultRequestAttachments::ref)
                .toList();
    }

    private static AttachmentRef ref(DocumentService.DocumentView d) {
        return new AttachmentRef(d.id(), d.fileName(), d.contentType(), d.sizeBytes(),
                d.title(), d.uploadedBy(), d.uploadedAt());
    }
}
