package com.seastella.identity.internal;

import com.seastella.core.api.error.ForbiddenException;
import com.seastella.core.api.error.NotFoundException;
import com.seastella.identity.api.AccessScope;
import com.seastella.identity.api.ScopeGuard;
import com.seastella.identity.api.ScopeResolver;
import org.springframework.stereotype.Component;

@Component
class DefaultScopeGuard implements ScopeGuard {

    private final ScopeResolver scopeResolver;

    DefaultScopeGuard(ScopeResolver scopeResolver) {
        this.scopeResolver = scopeResolver;
    }

    /**
     * Throws NotFound - never Forbidden - for an out-of-scope vessel, so a
     * caller cannot tell an id that does not exist from one that belongs to
     * someone else (SEC-08).
     */
    @Override
    public void assertVessel(Long vesselId) {
        AccessScope scope = scopeResolver.currentScope();
        if (!scope.permitsVessel(vesselId)) {
            throw NotFoundException.ofResource("Vessel", vesselId);
        }
    }

    @Override
    public void assertOrganization(Long organizationId) {
        AccessScope scope = scopeResolver.currentScope();
        if (!scope.permitsOrganization(organizationId)) {
            throw NotFoundException.ofResource("Organization", organizationId);
        }
    }

    /**
     * Financial access is a capability, not a scope, so this one <em>is</em>
     * Forbidden: the Captain can see the request, just not its cost (SoW s12).
     */
    @Override
    public void assertFinancialAccess() {
        if (!scopeResolver.currentScope().canSeeFinancials()) {
            throw ForbiddenException.ofAction("view financial information");
        }
    }
}
