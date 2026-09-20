package com.seastella.identity.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(
        prefix = "seastella.seed",
        name = "password-sync-enabled",
        havingValue = "true"
)
public class SeedPasswordSyncRunner implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(SeedPasswordSyncRunner.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String demoPassword;

    SeedPasswordSyncRunner(
            AppUserRepository users,
            PasswordEncoder passwordEncoder,
            @Value("${seastella.seed.demo-password:SeaStella#Demo2026}")
            String demoPassword) {

        if (demoPassword == null || demoPassword.length() < 12) {
            throw new IllegalStateException(
                    "seastella.seed.demo-password must be at least 12 characters");
        }

        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.demoPassword = demoPassword;
    }

    @Override
    public void run(ApplicationArguments args) {

        List<String> demoEmails = List.of(
                "admin@seastella.example",
                "tech.head@acme-shipmanagement.example",
                "d.fernandes@acme-shipmanagement.example",
                "master.kestrel@acme-shipmanagement.example",
                "coordinator@seastella.example",
                "t.okafor@marine-electronics.example"
        );

        int updated = 0;
        int skipped = 0;

        for (String email : demoEmails) {

            var optionalUser = users.findByEmailIgnoreCase(email);

            if (optionalUser.isEmpty()) {
                log.warn("Demo password sync: user not found: {}", email);
                skipped++;
                continue;
            }

            AppUser user = optionalUser.get();

            if (!"SEED".equals(user.getSeedMarker())) {
                log.warn(
                        "Demo password sync: refusing non-SEED user: {}",
                        email
                );
                skipped++;
                continue;
            }

            if (!passwordEncoder.matches(
                    demoPassword,
                    user.getPasswordHash())) {

                user.changePassword(
                        passwordEncoder.encode(demoPassword)
                );

                users.save(user);

                log.info(
                        "Demo password synchronized: {}",
                        email
                );

                updated++;
            } else {
                log.info(
                        "Demo password already synchronized: {}",
                        email
                );

                skipped++;
            }
        }

        log.info(
                "Demo password synchronization complete: updated={}, skipped={}",
                updated,
                skipped
        );
    }
}