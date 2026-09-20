package com.seastella.fleet.internal;

import com.seastella.core.api.error.NotFoundException;
import com.seastella.core.api.upload.UploadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;

/**
 * Where uploaded files live.
 *
 * <p>Outside the database and outside anything the web server serves (SEC-16).
 * The name on disk is chosen here - 32 random hex characters under a
 * year/month folder - so no caller-supplied text ever reaches a path, and a
 * file cannot be written or read outside the configured directory. Every
 * resolved path is checked against that directory before use, which is the
 * belt to the braces of not using caller input at all.
 */
@Component
class DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorage.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;

    DocumentStorage(UploadProperties properties) {
        this.root = Path.of(properties.getStorageDir()).toAbsolutePath().normalize();
    }

    /** @return the storage key to record against the document. */
    String write(byte[] content) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        byte[] name = new byte[16];
        RANDOM.nextBytes(name);
        String key = "%d/%02d/%s".formatted(today.getYear(), today.getMonthValue(), HexFormat.of().formatHex(name));
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the uploaded file", e);
        }
    }

    byte[] read(String storageKey) {
        Path path = resolve(storageKey);
        if (!Files.isRegularFile(path)) {
            // The record exists but the file does not: a restore gone wrong, or a
            // deployment whose storage directory moved. Say so plainly.
            log.error("document-file-missing key={}", storageKey);
            throw NotFoundException.ofResource("Document file", null);
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the stored file", e);
        }
    }

    /** Files are kept when a document is removed; this is only for a failed upload. */
    void deleteQuietly(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            log.warn("Could not delete the stored file {}", storageKey, e);
        }
    }

    private Path resolve(String storageKey) {
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Storage key escapes the storage directory");
        }
        return path;
    }
}
