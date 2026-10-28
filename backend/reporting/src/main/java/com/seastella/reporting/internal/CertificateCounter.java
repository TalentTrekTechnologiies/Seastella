package com.seastella.reporting.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * The last SBMC number issued in a year. Taken under a row lock: two
 * certificates generated at the same moment wait for each other rather than
 * share a number.
 */
@Entity
@Table(name = "certificate_counter")
class CertificateCounter {

    @Id
    @Column(name = "counter_year")
    private Integer year;

    @Column(name = "last_number", nullable = false)
    private int lastNumber;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected CertificateCounter() {
    }

    CertificateCounter(int year) {
        this.year = year;
        this.createdAt = Instant.now();
    }

    int next() {
        lastNumber++;
        updatedAt = Instant.now();
        return lastNumber;
    }
}
