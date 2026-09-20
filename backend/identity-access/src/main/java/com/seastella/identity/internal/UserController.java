package com.seastella.identity.internal;

import com.seastella.identity.api.Role;
import com.seastella.identity.api.UserDirectory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * People a user needs to choose between while working.
 *
 * <p>Only the Service Coordinator assigns engineers (RBAC matrix), so only the
 * Coordinator can list them. The response carries no organization data.
 */
@RestController
@RequestMapping("/api/v1/users")
class UserController {

    private final UserDirectory directory;

    UserController(UserDirectory directory) {
        this.directory = directory;
    }

    @GetMapping("/engineers")
    @PreAuthorize("hasRole('SERVICE_COORDINATOR')")
    ResponseEntity<List<EngineerOption>> engineers() {
        return ResponseEntity.ok(directory.activeByRole(Role.SERVICE_ENGINEER).stream()
                .map(u -> new EngineerOption(u.id(), u.fullName(), u.email()))
                .toList());
    }

    record EngineerOption(Long id, String fullName, String email) {}
}
