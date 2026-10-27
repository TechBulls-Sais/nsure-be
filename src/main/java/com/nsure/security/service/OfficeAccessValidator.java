package com.nsure.security.service;

import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import org.springframework.stereotype.Component;

@Component
public class OfficeAccessValidator {

    public void validateAccess(String resourceOfficeHierarchy, String userOfficeHierarchy) {
        if (resourceOfficeHierarchy == null
                || userOfficeHierarchy == null
                || !resourceOfficeHierarchy.startsWith(userOfficeHierarchy)) {
            throw new NoAuthorizationException("The user doesn't have enough permissions to access the resource.");
        }
    }

    public boolean isHeadOffice(String userOfficeHierarchy) {
        return ".".equals(userOfficeHierarchy);
    }

    public String hierarchyOf(AppUser user) {
        if (user.getOffice() == null || user.getOffice().getHierarchy() == null) {
            throw new NoAuthorizationException("The user doesn't have enough permissions to access the resource.");
        }
        return user.getOffice().getHierarchy();
    }
}
