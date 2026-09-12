package com.seastella.identity.internal;

import org.springframework.data.domain.AuditorAware;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Populates created_by / updated_by on every BaseEntity. */
@Component("auditorAware")
class SpringSecurityAuditorAware implements AuditorAware<String> {

    @Override
    @NonNull
    public Optional<String> getCurrentAuditor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SeaStellaPrincipal p) {
            return Optional.of(p.getUsername());
        }
        return Optional.of("system");
    }
}
