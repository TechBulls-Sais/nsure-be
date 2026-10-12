package com.nsure.security.service;

import com.nsure.security.domain.AppUser;

public interface PlatformSecurityContext {

    AppUser authenticatedUser();

    AppUser getAuthenticatedUserIfPresent();
}
