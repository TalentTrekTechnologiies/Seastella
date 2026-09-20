package com.seastella.fleet.api;

import java.util.Collection;
import java.util.Map;

/**
 * Files attached to a service request (CHT-08).
 *
 * <p>An attachment is a document like any other - same storage, same
 * check-the-bytes rule, same vessel scope, same audit entry - so the chat does
 * not grow a second file store beside the first. The conversation keeps only
 * the id; this port carries the record, never the bytes. The file itself is
 * read through {@code /api/v1/documents/{id}/content}, which re-checks scope.
 *
 * <p>Fleet cannot see a service request - it sits below service-request in the
 * module order - so the caller supplies the vessel the request is on, and this
 * port re-checks that the caller is allowed near that vessel.
 */
public interface RequestAttachments {

    /**
     * Stores a file against a request.
     *
     * @throws com.seastella.core.api.error.NotFoundException   if the vessel is outside the caller's scope
     * @throws com.seastella.core.api.error.ForbiddenException  if the caller's role may not attach here
     * @throws com.seastella.core.api.error.ValidationException if the file is empty, too large or not an accepted type
     */
    AttachmentRef attach(Long vesselId, Long serviceRequestId, String fileName, byte[] content, String caption);

    /** The records for messages that carry an attachment, by document id. */
    Map<Long, AttachmentRef> describe(Collection<Long> documentIds);

    /**
     * Everything filed against one request, newest first — the photographs the
     * Captain raised it with (SoW §6.1) and anything sent in the chat since.
     *
     * @throws com.seastella.core.api.error.NotFoundException if the vessel is outside the caller's scope
     */
    java.util.List<AttachmentRef> forRequest(Long vesselId, Long serviceRequestId);

    record AttachmentRef(Long documentId, String fileName, String contentType, long sizeBytes,
                         String title, String uploadedBy, java.time.Instant uploadedAt) {

        /** Shown inline in the transcript rather than offered as a download. */
        public boolean isImage() {
            return contentType != null && contentType.startsWith("image/");
        }

        public boolean isVideo() {
            return contentType != null && contentType.startsWith("video/");
        }
    }
}
