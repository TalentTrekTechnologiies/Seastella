package com.seastella.identity.internal;

import com.seastella.identity.api.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Creates the first Platform Admin on an empty installation.
 *
 * <p>Every other account is created inside the app by someone above it in the
 * SoW s4.1 chain, so the chain needs one account to start from. It is taken
 * from {@code BOOTSTRAP_ADMIN_EMAIL} and {@code BOOTSTRAP_ADMIN_PASSWORD} and
 * created only while no Platform Admin exists: once one does, the variables
 * are ignored and can be removed, and they never overwrite an existing account.
 */
@Component
class FirstAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FirstAdminBootstrap.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;
    private final String fullName;

    FirstAdminBootstrap(AppUserRepository users,
                        PasswordEncoder passwordEncoder,
                        @Value("${seastella.bootstrap.admin-email:}") String email,
                        @Value("${seastella.bootstrap.admin-password:}") String password,
                        @Value("${seastella.bootstrap.admin-name:Platform Administrator}") String fullName) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        this.password = password == null ? "" : password;
        this.fullName = fullName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.countByRole(Role.PLATFORM_ADMIN) > 0) {
            return;
        }
        if (email.isEmpty() || password.isEmpty()) {
            log.warn("No Platform Admin exists yet. Set BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD "
                    + "and restart to create the first one.");
            return;
        }
        if (users.existsByEmailIgnoreCase(email)) {
            log.warn("First Platform Admin not created: {} already belongs to another account.", email);
            return;
        }
        PasswordPolicy.check(password, email);

        // A Platform Admin belongs to no organization (database CHECK).
        users.save(new AppUser(email, passwordEncoder.encode(password), fullName, Role.PLATFORM_ADMIN, null));
        log.info("First Platform Admin created: {}. BOOTSTRAP_ADMIN_PASSWORD can now be removed.", email);
    }
}
