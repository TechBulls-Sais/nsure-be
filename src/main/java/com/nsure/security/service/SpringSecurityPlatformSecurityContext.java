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
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUser user)) {
            return null;
        }
        return user;
    }
}
