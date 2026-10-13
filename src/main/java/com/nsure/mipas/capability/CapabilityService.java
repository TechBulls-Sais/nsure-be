package com.nsure.mipas.capability;

import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.PermissionCodes;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CapabilityService {

    private final CapabilityGrantRepository grants;
    private final PlatformSecurityContext security;

    public CapabilityService(CapabilityGrantRepository grants, PlatformSecurityContext security) {
        this.grants = grants;
        this.security = security;
    }

    public boolean has(Capability capability) {
        Capability.fromCode(capability.code());
        AppUser user = security.authenticatedUser();
        if (user.hasPermissionTo(PermissionCodes.ALL_FUNCTIONS)) {
            return true;
        }
        return user.getRoles().stream()
                .filter(role -> !role.isDisabled())
                .flatMap(role -> grants.codesForRole(role.getName()).stream())
                .anyMatch(code -> code.equals(capability.code()));
    }

    public void require(Capability capability) {
        if (!has(capability)) {
            throw new NoAuthorizationException("Missing capability " + capability.code());
        }
    }

    public List<String> codesForRole(String roleName) {
        return grants.codesForRole(roleName);
    }
}
