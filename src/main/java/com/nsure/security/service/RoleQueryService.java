package com.nsure.security.service;

import com.nsure.security.api.RoleResponse;
import com.nsure.security.domain.Permission;
import com.nsure.security.domain.Role;
import com.nsure.security.repository.RoleRepository;
import com.nsure.common.exception.NotFoundException;
import java.util.Comparator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RoleQueryService {

    private final RoleRepository roles;
    private final PlatformSecurityContext security;

    public RoleQueryService(RoleRepository roles, PlatformSecurityContext security) {
        this.roles = roles;
        this.security = security;
    }

    public java.util.List<RoleResponse> list() {
        security.authenticatedUser().validateHasReadPermission("ROLE");
        return roles.findAllByOrderByNameAsc().stream().map(this::toResponse).toList();
    }

    public RoleResponse get(Long id) {
        security.authenticatedUser().validateHasReadPermission("ROLE");
        return toResponse(roles.findById(id).orElseThrow(() -> new NotFoundException("Role not found")));
    }

    private RoleResponse toResponse(Role role) {
        java.util.List<String> codes = role.getPermissions() == null
                ? java.util.List.of()
                : role.getPermissions().stream()
                        .map(Permission::getCode)
                        .sorted(Comparator.naturalOrder())
                        .toList();
        return new RoleResponse(
                role.getId(),
                role.getName(),
                role.getDescription(),
                role.isDisabled(),
                role.isDeskAccess(),
                role.isProtectedRole(),
                codes,
                role.getCreatedBy(),
                role.getCreatedDate(),
                role.getLastModifiedBy(),
                role.getLastModifiedDate());
    }
}
