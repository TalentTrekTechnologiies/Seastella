package com.seastella.identity.internal;

import com.seastella.identity.api.Role;
import com.seastella.identity.api.UserDirectory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
class DefaultUserDirectory implements UserDirectory {

    private final AppUserRepository users;

    DefaultUserDirectory(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserRef> find(Long userId) {
        if (userId == null) return Optional.empty();
        return users.findById(userId).map(DefaultUserDirectory::toRef);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, UserRef> findAll(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        return users.findAllById(userIds.stream().filter(java.util.Objects::nonNull).distinct().toList())
                .stream()
                .map(DefaultUserDirectory::toRef)
                .collect(Collectors.toMap(UserRef::id, Function.identity()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserRef> activeByRole(Role role) {
        return users.findByRoleOrderByFullNameAsc(role).stream()
                .filter(AppUser::isActive)
                .map(DefaultUserDirectory::toRef)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserRef> activeOnVessel(Role role, Long vesselId) {
        if (vesselId == null) return List.of();
        return users.findActiveByRoleOnVessel(role, vesselId).stream().map(DefaultUserDirectory::toRef).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserRef> activeForOrganization(Role role, Long organizationId) {
        if (organizationId == null) return List.of();
        return users.findActiveByRoleForOrganization(role, organizationId).stream()
                .map(DefaultUserDirectory::toRef).toList();
    }

    private static UserRef toRef(AppUser u) {
        return new UserRef(u.getId(), u.getFullName(), u.getEmail(), u.getRole(), u.getOrganizationId(), u.isActive());
    }
}
