package com.seastella.core.api.upload;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Binds {@code seastella.upload.*} (SEC-15, DOC-09 / OI-09). */
@ConfigurationProperties(prefix = "seastella.upload")
public class UploadProperties {

    /** Hard ceiling per file, in bytes. Spring's multipart limit is set to match. */
    private long maxBytes = 26_214_400L;

    /** The only media types accepted, whatever a file calls itself. */
    private List<String> allowedTypes = List.of(
            "application/pdf", "image/png", "image/jpeg", "image/webp",
            // A Captain photographing or filming a fault is the point of CHT-08.
            "video/mp4", "video/quicktime",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    /** Where files are written. Must be outside anything the web server serves (SEC-16). */
    private String storageDir = "./data/documents";

    public long getMaxBytes() { return maxBytes; }
    public void setMaxBytes(long maxBytes) { this.maxBytes = maxBytes; }
    public List<String> getAllowedTypes() { return allowedTypes; }
    public void setAllowedTypes(List<String> allowedTypes) { this.allowedTypes = allowedTypes; }
    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
}
