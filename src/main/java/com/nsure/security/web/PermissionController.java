package com.nsure.security.web;

import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Permission;
import com.nsure.security.repo.PermissionRepository;
import com.nsure.security.service.PlatformSecurityContext;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/permissions")
public class PermissionController {

    private final PlatformSecurityContext security;
    private final PermissionRepository permissions;

    public PermissionController(PlatformSecurityContext security, PermissionRepository permissions) {
        this.security = security;
        this.permissions = permissions;
    }

    @GetMapping
    public List<PermissionResponse> list() {
        AppUser user = security.authenticatedUser();
        if (!user.hasPermissionTo("PERMISSIONS_ROLE")) {
            user.validateHasReadPermission("PERMISSION");
        }
        return permissions.findAll().stream()
                .sorted(Comparator.comparing(Permission::getGrouping).thenComparing(Permission::getCode))
                .map(permission -> new PermissionResponse(
                        permission.getId(),
                        permission.getGrouping(),
                        permission.getCode(),
                        permission.getEntityName(),
                        permission.getActionName(),
                        permission.isCanMakerChecker()))
                .toList();
    }
}
