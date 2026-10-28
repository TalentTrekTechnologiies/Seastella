package com.seastella.reporting.internal;

import com.seastella.core.api.time.BusinessTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out SBMC numbers: TM-2026-0001, the year of issue and its sequence,
 * never reused. Part of the caller's transaction, so an issue that fails gives
 * its number back rather than leaving a gap that looks like a lost certificate.
 */
@Component
class CertificateNumbers {

    private final EntityManager em;

    CertificateNumbers(EntityManager em) {
        this.em = em;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    String nextSbmcNumber() {
        int year = BusinessTime.today().getYear();
        CertificateCounter counter = em.find(CertificateCounter.class, year, LockModeType.PESSIMISTIC_WRITE);
        if (counter == null) {
            em.persist(new CertificateCounter(year));
            em.flush();
            counter = em.find(CertificateCounter.class, year, LockModeType.PESSIMISTIC_WRITE);
        }
        return String.format("TM-%d-%04d", year, counter.next());
    }
}
