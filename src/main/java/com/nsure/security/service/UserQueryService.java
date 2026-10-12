package com.nsure.security.service;

import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Role;
import com.nsure.security.repo.AppUserRepository;
import com.nsure.security.web.UserResponse;
import com.nsure.web.ResourceNotFoundException;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserQueryService {

    private final PlatformSecurityContext security;
    private final OfficeAccess officeAccess;
    private final AppUserRepository users;

    public UserQueryService(PlatformSecurityContext security, OfficeAccess officeAccess, AppUserRepository users) {
        this.security = security;
        this.officeAccess = officeAccess;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        AppUser actor = security.authenticatedUser();
        actor.validateHasReadPermission("USER");
        boolean headOffice = officeAccess.isHeadOffice(actor);
        String hierarchy = officeAccess.hierarchyOf(actor.getOfficeId());
        if (!headOffice && hierarchy == null) {
            return List.of();
        }
        return users.findVisible(headOffice, hierarchy == null ? "" : hierarchy).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long id) {
        AppUser actor = security.authenticatedUser();
        actor.validateHasReadPermission("USER");
        AppUser user = users.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User " + id + " not found"));
        officeAccess.validateResourceOffice(user.getOfficeId(), actor);
        return toResponse(user);
    }

    private UserResponse toResponse(AppUser user) {
        List<UserResponse.RoleSummary> roles = user.getRoles().stream()
                .sorted(Comparator.comparing(Role::getName))
                .map(role -> new UserResponse.RoleSummary(role.getId(), role.getName()))
                .toList();
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.isEnabled(),
                user.getOfficeId(),
                roles,
                user.getCreatedBy(),
                user.getCreatedDate(),
                user.getLastModifiedBy(),
                user.getLastModifiedDate());
    }
}
