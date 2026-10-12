package com.nsure.security.service;

import com.nsure.security.domain.Permission;
import com.nsure.security.domain.Role;
import com.nsure.security.repo.RoleRepository;
import com.nsure.security.web.RoleResponse;
import com.nsure.web.ResourceNotFoundException;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleQueryService {

    private final PlatformSecurityContext security;
    private final RoleRepository roles;

    public RoleQueryService(PlatformSecurityContext security, RoleRepository roles) {
        this.security = security;
        this.roles = roles;
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> list() {
        security.authenticatedUser().validateHasReadPermission("ROLE");
        return roles.findAll().stream()
                .sorted(Comparator.comparing(Role::getName))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RoleResponse get(Long id) {
        security.authenticatedUser().validateHasReadPermission("ROLE");
        return toResponse(roles.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Role " + id + " not found")));
    }

    private RoleResponse toResponse(Role role) {
        List<String> permissions = role.getPermissions().stream()
                .map(Permission::getCode)
                .sorted()
                .toList();
        return new RoleResponse(
                role.getId(),
                role.getName(),
                role.getDescription(),
                role.isDisabled(),
                permissions,
                role.getCreatedBy(),
                role.getCreatedDate(),
                role.getLastModifiedBy(),
                role.getLastModifiedDate());
    }
}
