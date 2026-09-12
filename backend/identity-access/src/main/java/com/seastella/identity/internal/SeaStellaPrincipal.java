package com.seastella.identity.internal;

import com.seastella.identity.api.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/** The authenticated principal carried on the SecurityContext. */
public class SeaStellaPrincipal implements UserDetails {

    private final Long userId;
    private final String email;
    private final Role role;
    private final Long organizationId;
    private final String fullName;

    public SeaStellaPrincipal(Long userId, String email, Role role, Long organizationId, String fullName) {
        this.userId = userId;
        this.email = email;
        this.role = role;
        this.organizationId = organizationId;
        this.fullName = fullName;
    }

    public Long userId() { return userId; }
    public Role role() { return role; }
    public Long organizationId() { return organizationId; }
    public String fullName() { return fullName; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override public String getPassword() { return null; }
    @Override public String getUsername() { return email; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
