package com.seastella.core.api.upload;

import com.seastella.core.api.error.ValidationException;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * What may be uploaded (SEC-15).
 *
 * <p>The type is decided by the bytes, not by the name or by what the browser
 * claims: {@code invoice.pdf} that begins {@code MZ} is a Windows executable,
 * and a client can send any {@code Content-Type} it likes. A file is accepted
 * only when its leading bytes identify one of the allowed types <em>and</em>
 * that type is on the allow-list. Everything else is refused with the same
 * short sentence, which is also what stops an upload endpoint being used to
 * probe what the server will run.
 */
@Component
public class UploadPolicy {

    /** What was checked, for the caller to store beside the file. */
    public record Checked(String contentType, long sizeBytes, String sha256, String fileName) {}

    private record Signature(String contentType, int offset, int[] magic) {}

    private static final List<Signature> SIGNATURES = List.of(
            new Signature("application/pdf", 0, new int[]{0x25, 0x50, 0x44, 0x46}),                 // %PDF
            new Signature("image/png", 0, new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}),
            new Signature("image/jpeg", 0, new int[]{0xFF, 0xD8, 0xFF}),
            new Signature("image/webp", 8, new int[]{0x57, 0x45, 0x42, 0x50}),                      // ....WEBP
            // ISO base media: MP4 and QuickTime both begin with an ftyp box.
            new Signature("video/mp4", 4, new int[]{0x66, 0x74, 0x79, 0x70}),
            // Office files are ZIP archives; which Office file is settled below.
            new Signature("application/zip", 0, new int[]{0x50, 0x4B, 0x03, 0x04}),
            new Signature("application/zip", 0, new int[]{0x50, 0x4B, 0x05, 0x06}),
            new Signature("application/zip", 0, new int[]{0x50, 0x4B, 0x07, 0x08}));

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final UploadProperties properties;

    public UploadPolicy(UploadProperties properties) {
        this.properties = properties;
    }

    public long maxBytes() {
        return properties.getMaxBytes();
    }

    public List<String> allowedTypes() {
        return List.copyOf(properties.getAllowedTypes());
    }

    /**
     * @throws ValidationException if the file is empty, too large, or not a type
     *                             this platform accepts.
     */
    public Checked check(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new ValidationException("That file is empty.");
        }
        if (content.length > properties.getMaxBytes()) {
            throw new ValidationException("That file is larger than "
                    + (properties.getMaxBytes() / (1024 * 1024)) + " MB.");
        }
        String safeName = safeName(fileName);
        String detected = detect(content, safeName);
        if (detected == null || !properties.getAllowedTypes().contains(detected)) {
            throw new ValidationException("That kind of file is not accepted. Upload a PDF, an image (PNG, JPEG, "
                    + "WebP), a video (MP4, QuickTime), a Word document or a spreadsheet.");
        }
        return new Checked(detected, content.length, sha256(content), safeName);
    }

    /** The name is only ever a label: the file is stored under a name the platform chooses. */
    public static String safeName(String raw) {
        if (raw == null || raw.isBlank()) return "document";
        String name = raw.replaceAll("[\\p{Cntrl}]", "")
                .replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return "document";
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private String detect(byte[] content, String fileName) {
        for (Signature signature : SIGNATURES) {
            if (matches(content, signature)) {
                if ("application/zip".equals(signature.contentType())) return office(fileName);
                if ("video/mp4".equals(signature.contentType())) return isoBaseMedia(content);
                return signature.contentType();
            }
        }
        return null;
    }

    /**
     * An ftyp box says which flavour of ISO base media this is. QuickTime brands
     * itself {@code qt  }; everything else the platform accepts is MP4.
     */
    private static String isoBaseMedia(byte[] content) {
        if (content.length < 12) return "video/mp4";
        String brand = new String(content, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return "qt  ".equals(brand) ? "video/quicktime" : "video/mp4";
    }

    /**
     * A .docx and a .xlsx are both ZIP archives, and telling them apart properly
     * means reading the archive. The extension decides which of the two allowed
     * Office types it is; anything else that is a ZIP is refused.
     */
    private static String office(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx")) return XLSX;
        if (lower.endsWith(".docx")) return DOCX;
        return null;
    }

    private static boolean matches(byte[] content, Signature signature) {
        int[] magic = signature.magic();
        if (content.length < signature.offset() + magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if ((content[signature.offset() + i] & 0xFF) != magic[i]) return false;
        }
        return true;
    }

    /** Stored with the record so a file can be shown to be the one that was uploaded. */
    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
