package com.seastella.identity.internal;

import com.seastella.core.api.security.ActorProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Supplies the acting user to platform-core, which sits below this module. */
@Component
class DefaultActorProvider implements ActorProvider {

    @Override
    public Optional<Actor> currentActor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SeaStellaPrincipal p) {
            return Optional.of(new Actor(p.userId(), p.getUsername(), p.role().name(), p.organizationId()));
        }
        return Optional.empty();
    }
}
