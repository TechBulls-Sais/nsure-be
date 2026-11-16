package com.nsure.security.service;

import com.nsure.common.exception.NotFoundException;
import com.nsure.security.api.PermissionResponse;
import com.nsure.security.domain.Permission;
import com.nsure.security.repository.PermissionRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PermissionQueryService {

    private final PermissionRepository permissions;
    private final PlatformSecurityContext security;

    public PermissionQueryService(
            PermissionRepository permissions, PlatformSecurityContext security) {
        this.permissions = permissions;
        this.security = security;
    }

    public List<PermissionResponse> list() {
        security.authenticatedUser().validateHasReadPermission("PERMISSION");
        return permissions.findAllByOrderByGroupingAscCodeAsc().stream().map(this::toResponse).toList();
    }

    public PermissionResponse get(Long id) {
        security.authenticatedUser().validateHasReadPermission("PERMISSION");
        return toResponse(permissions.findById(id)
                .orElseThrow(() -> new NotFoundException("Permission not found")));
    }

    private PermissionResponse toResponse(Permission permission) {
        return new PermissionResponse(
                permission.getId(),
                permission.getGrouping(),
                permission.getCode(),
                permission.getEntityName(),
                permission.getActionName(),
                permission.getFunctionName(),
                permission.isCanMakerChecker(),
                permission.getCapability());
    }
}
