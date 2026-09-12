package com.seastella.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Composition root for the SeaStella platform.
 *
 * <p>The platform is a modular monolith: one deployable assembled from the
 * independently-compiled modules under {@code backend/}. Component scanning is
 * rooted at {@code com.seastella} so every module contributes its beans, while
 * the module boundaries themselves are enforced at build time by ArchUnit
 * (see {@code ModuleBoundaryTest}) rather than by scan configuration.
 *
 * @see <a href="file:../../docs/02-architecture.md">docs/02-architecture.md</a>
 */
@SpringBootApplication(scanBasePackages = "com.seastella")
@ConfigurationPropertiesScan("com.seastella")
@EntityScan(basePackages = "com.seastella")
@EnableJpaRepositories(basePackages = "com.seastella")
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
@EnableScheduling
@EnableAsync
public class SeaStellaApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeaStellaApplication.class, args);
    }
}
