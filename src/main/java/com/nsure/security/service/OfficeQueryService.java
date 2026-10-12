package com.nsure.security.service;

import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Office;
import com.nsure.security.repo.OfficeRepository;
import com.nsure.security.web.OfficeResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfficeQueryService {

    private final PlatformSecurityContext security;
    private final OfficeAccess officeAccess;
    private final OfficeRepository offices;

    public OfficeQueryService(PlatformSecurityContext security, OfficeAccess officeAccess, OfficeRepository offices) {
        this.security = security;
        this.officeAccess = officeAccess;
        this.offices = offices;
    }

    @Transactional(readOnly = true)
    public List<OfficeResponse> list() {
        AppUser actor = security.authenticatedUser();
        if (!actor.hasPermissionTo("CREATE_USER") && !actor.hasPermissionTo("UPDATE_USER")) {
            actor.validateHasReadPermission("OFFICE");
        }
        boolean headOffice = officeAccess.isHeadOffice(actor);
        String hierarchy = officeAccess.hierarchyOf(actor.getOfficeId());
        if (!headOffice && hierarchy == null) {
            return List.of();
        }
        return offices.findVisible(headOffice, hierarchy == null ? "" : hierarchy).stream()
                .map(office -> new OfficeResponse(office.getId(), office.getName(), office.getHierarchy()))
                .toList();
    }
}
