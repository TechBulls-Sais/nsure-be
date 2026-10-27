package com.nsure.security.service;

import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.UnAuthenticatedUserException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SpringSecurityPlatformSecurityContext implements PlatformSecurityContext {

    @Override
    public AppUser authenticatedUser() {
        AppUser user = currentUserOrNull();
        if (user == null) {
            throw new UnAuthenticatedUserException();
        }
        return user;
    }

    @Override
    public AppUser getAuthenticatedUserIfPresent() {
        return currentUserOrNull();
    }

    private AppUser currentUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUser user)) {
            return null;
        }
        return user;
    }
}
