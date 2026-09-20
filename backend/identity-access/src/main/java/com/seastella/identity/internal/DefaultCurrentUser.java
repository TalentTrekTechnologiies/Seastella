package com.seastella.identity.internal;

import com.seastella.identity.api.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
class DefaultCurrentUser implements CurrentUser {

    @Override
    public Optional<Long> userId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof SeaStellaPrincipal principal
                ? Optional.of(principal.userId())
                : Optional.empty();
    }
}
