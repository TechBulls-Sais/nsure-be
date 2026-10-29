package com.nsure.audit.service;

import com.nsure.audit.domain.CommandSource;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.PermissionCode;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.repository.PermissionRepository;
import org.springframework.stereotype.Component;

@Component
public class MakerCheckerPolicy {

    private final PermissionRepository permissions;
    private final MakerCheckerProperties properties;

    public MakerCheckerPolicy(PermissionRepository permissions, MakerCheckerProperties properties) {
        this.permissions = permissions;
        this.properties = properties;
    }

    public boolean requiresChecker(AppUser user, String entity, String action, Long resourceId) {
        String permissionCode = PermissionCode.code(action, entity);
        boolean enabled = permissions.findByCodeIgnoreCase(permissionCode)
                .map(permission -> permission.isCanMakerChecker())
                .orElse(false);
        if (!enabled || user.hasPermissionTo("CHECKER_SUPER_USER")) {
            return false;
        }
        return !isSelfProfile(user, entity, action, resourceId);
    }

    public void validateChecker(AppUser checker, CommandSource pending) {
        String required = "CHECKER_" + pending.getPermissionCode();
        if (!checker.hasPermissionTo("CHECKER_SUPER_USER") && !checker.hasPermissionTo(required)) {
            throw new NoAuthorizationException("User has no authority to: " + required);
        }
        // Super user and Super admin may check a command they made; every other maker still needs a different checker.
        if (!properties.isSameUserAllowed()
                && checker.getId().equals(pending.getMakerId())
                && !checker.isSuperAdmin()) {
            throw new NoAuthorizationException("Maker cannot check their own command");
        }
    }

    private boolean isSelfProfile(AppUser user, String entity, String action, Long resourceId) {
        return "USER".equalsIgnoreCase(entity)
                && "UPDATE".equalsIgnoreCase(action)
                && resourceId != null
                && resourceId.equals(user.getId());
    }
}
