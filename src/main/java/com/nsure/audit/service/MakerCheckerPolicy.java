package com.nsure.audit.service;

import com.nsure.audit.domain.CommandSource;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.PermissionCode;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.repository.PermissionRepository;
import org.springframework.stereotype.Component;

/**
 * Four-eyes rules for commands. A command whose permission is marked maker-checker always waits for a checker, whoever
 * made it (only a user's own profile edit skips it). The checker needs {@code CHECKER_<code>} and is never the maker:
 * no one approves their own work, the administrator included (Nsure Life NS-UX-004, NS-SH10-003, AT-SH10-01).
 */
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
        if (!enabled) {
            return false;
        }
        return !isSelfProfile(user, entity, action, resourceId);
    }

    public void validateChecker(AppUser checker, CommandSource pending) {
        String required = "CHECKER_" + pending.getPermissionCode();
        if (!checker.hasPermissionTo(required)) {
            throw new NoAuthorizationException("User has no authority to: " + required);
        }
        if (!properties.isSameUserAllowed() && checker.getId().equals(pending.getMakerId())) {
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
