package com.nsure.security.service;

import com.nsure.security.api.OfficeResponse;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Office;
import com.nsure.security.repository.OfficeRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OfficeQueryService {

    private final OfficeRepository offices;
    private final PlatformSecurityContext security;
    private final OfficeAccessValidator access;

    public OfficeQueryService(
            OfficeRepository offices, PlatformSecurityContext security, OfficeAccessValidator access) {
        this.offices = offices;
        this.security = security;
        this.access = access;
    }

    public List<OfficeResponse> list() {
        AppUser caller = security.authenticatedUser();
        caller.validateHasReadPermission("OFFICE");
        return offices.findInHierarchy(access.hierarchyOf(caller)).stream().map(this::toResponse).toList();
    }

    private OfficeResponse toResponse(Office office) {
        Long parentId = office.getParent() == null ? null : office.getParent().getId();
        return new OfficeResponse(office.getId(), office.getName(), office.getHierarchy(), parentId);
    }
}
