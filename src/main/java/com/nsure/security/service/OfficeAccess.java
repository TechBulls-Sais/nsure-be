package com.nsure.security.service;

import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Office;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.repo.OfficeRepository;
import org.springframework.stereotype.Component;

@Component
public class OfficeAccess {

    private final OfficeRepository offices;

    public OfficeAccess(OfficeRepository offices) {
        this.offices = offices;
    }

    public void validateAccess(String resourceOfficeHierarchy, String userOfficeHierarchy) {
        if (resourceOfficeHierarchy == null
                || userOfficeHierarchy == null
                || !resourceOfficeHierarchy.startsWith(userOfficeHierarchy)) {
            throw new NoAuthorizationException("The user doesn't have enough permissions to access the resource.");
        }
    }

    public void validateResourceOffice(Long resourceOfficeId, AppUser user) {
        String userHierarchy = hierarchyOf(user.getOfficeId());
        if (resourceOfficeId == null) {
            if (!Office.HEAD_OFFICE_HIERARCHY.equals(userHierarchy)) {
                throw new NoAuthorizationException("The user doesn't have enough permissions to access the resource.");
            }
            return;
        }
        validateAccess(hierarchyOf(resourceOfficeId), userHierarchy);
    }

    public boolean isHeadOffice(AppUser user) {
        return Office.HEAD_OFFICE_HIERARCHY.equals(hierarchyOf(user.getOfficeId()));
    }

    public String hierarchyOf(Long officeId) {
        if (officeId == null) {
            return null;
        }
        return offices.findById(officeId)
                .map(Office::getHierarchy)
                .orElseThrow(() -> new IllegalArgumentException("Unknown office: " + officeId));
    }
}
