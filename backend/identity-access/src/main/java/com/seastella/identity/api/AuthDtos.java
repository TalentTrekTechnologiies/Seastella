package com.seastella.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response shapes for authentication. */
public final class AuthDtos {

    private AuthDtos() {}

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 200) String password) {}

    /**
     * The signed-in session.
     *
     * <p>Carries the role and the vessel scope so the frontend can route and
     * render without a second call. None of it is authoritative: the server
     * re-resolves scope on every request, and this payload is a convenience for
     * the UI, never the basis of an access decision.
     */
    public record LoginResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            UserProfile user) {}

    public record UserProfile(
            Long id,
            String email,
            String fullName,
            Role role,
            String roleLabel,
            Long organizationId,
            java.util.Set<Long> vesselIds,
            boolean canSeeFinancials) {}
}
