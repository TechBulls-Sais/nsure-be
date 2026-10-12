package com.nsure.audit;

import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.PermissionCodes;
import com.nsure.security.repo.PermissionRepository;
import org.springframework.stereotype.Component;

@Component
public class MakerCheckerPolicy {

    private final PermissionRepository permissions;

    public MakerCheckerPolicy(PermissionRepository permissions) {
        this.permissions = permissions;
    }

    public boolean isEnabled(String permissionCode) {
        return permissions.findByCodeIgnoreCase(permissionCode)
                .map(permission -> permission.isCanMakerChecker())
                .orElse(false);
    }

    /**
     * A checker super user commits directly. A user changing their own profile also skips maker-checker.
     */
    public boolean requiresApproval(AppUser user, String permissionCode, Long resourceId) {
        if (!isEnabled(permissionCode)) {
            return false;
        }
        if (user.hasPermissionTo(PermissionCodes.CHECKER_SUPER_USER)) {
            return false;
        }
        return !isSelfUserUpdate(user, permissionCode, resourceId);
    }

    private boolean isSelfUserUpdate(AppUser user, String permissionCode, Long resourceId) {
        return "UPDATE_USER".equalsIgnoreCase(permissionCode)
                && resourceId != null
                && resourceId.equals(user.getId());
    }
}
