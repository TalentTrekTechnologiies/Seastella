package com.seastella.app.seed;

import com.seastella.core.api.seed.SeedContext;
import com.seastella.core.api.seed.SeedContributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Runs each module's seed contributor in order, in one transaction.
 *
 * <p>One transaction on purpose: the dataset is only useful if it is
 * relationally complete. A half-seeded database - vessels but no spares, or
 * requests whose invoices failed to write - would make the dashboards display
 * figures that are wrong rather than empty, which is harder to notice.
 *
 * <p>Disabled unless {@code seastella.seed.enabled} is true, and that property
 * is false in the prod profile (NFR-11).
 */
@Component
@ConditionalOnProperty(prefix = "seastella.seed", name = "enabled", havingValue = "true")
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final List<SeedContributor> contributors;
    private final SeedGuard guard;

    SeedRunner(List<SeedContributor> contributors, SeedGuard guard) {
        this.contributors = contributors;
        this.guard = guard;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (guard.alreadySeeded()) {
            log.info("Demo data already present; skipping seed.");
            return;
        }

        SeedContext context = new SeedContext(LocalDate.now());

        List<SeedContributor> ordered = contributors.stream()
                .sorted(Comparator.comparingInt(SeedContributor::order))
                .toList();

        log.info("Seeding demo data ({} contributors). Rows are marked SEED and are "
                + "not client data.", ordered.size());

        for (SeedContributor contributor : ordered) {
            long started = System.currentTimeMillis();
            contributor.contribute(context);
            log.info("  [{}] {} ({} ms)", contributor.order(), contributor.name(),
                    System.currentTimeMillis() - started);
        }

        log.info("Seed complete: {} handles registered.", context.all().size());
        // The password itself is never logged: on a hosted demo it is a secret.
        log.info("Demo sign-in (every demo account shares seastella.seed.demo-password):");
        log.info("  admin@seastella.example                       Platform Admin");
        log.info("  tech.head@acme-shipmanagement.example         Technical Head");
        log.info("  d.fernandes@acme-shipmanagement.example       Ship Manager (2 vessels)");
        log.info("  master.kestrel@acme-shipmanagement.example    Captain (MV Kestrel Trader)");
        log.info("  coordinator@seastella.example                 Service Coordinator");
        log.info("  t.okafor@marine-electronics.example           Service Engineer");
    }
}
