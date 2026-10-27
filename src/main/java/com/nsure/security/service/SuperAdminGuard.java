package com.nsure.security.service;

import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import org.springframework.stereotype.Component;

@Component
public class SuperAdminGuard {

    private final PlatformSecurityContext security;

    public SuperAdminGuard(PlatformSecurityContext security) {
        this.security = security;
    }

    public AppUser require() {
        AppUser user = security.authenticatedUser();
        if (!user.isSuperAdmin()) {
            throw new NoAuthorizationException("Only a super admin can manage access.");
        }
        return user;
    }
}
