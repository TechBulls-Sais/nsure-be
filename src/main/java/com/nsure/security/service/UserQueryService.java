package com.nsure.security.service;

import com.nsure.common.exception.NotFoundException;
import com.nsure.security.api.UserResponse;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Role;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.repository.AppUserRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserQueryService {

    private final AppUserRepository users;
    private final PlatformSecurityContext security;
    private final OfficeAccessValidator access;
    private final SuperAdminGuard superAdmins;

    public UserQueryService(
            AppUserRepository users,
            PlatformSecurityContext security,
            OfficeAccessValidator access,
            SuperAdminGuard superAdmins) {
        this.users = users;
        this.security = security;
        this.access = access;
        this.superAdmins = superAdmins;
    }

    public List<UserResponse> list() {
        superAdmins.require();
        AppUser caller = security.authenticatedUser();
        caller.validateHasReadPermission("USER");
        return users.findInHierarchy(access.hierarchyOf(caller)).stream().map(this::toResponse).toList();
    }

    public UserResponse get(Long id) {
        superAdmins.require();
        AppUser caller = security.authenticatedUser();
        caller.validateHasReadPermission("USER");
        AppUser user = users.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        if (user.getOffice() == null) {
            throw new NoAuthorizationException("The user doesn't have enough permissions to access the resource.");
        }
        access.validateAccess(user.getOffice().getHierarchy(), access.hierarchyOf(caller));
        return toResponse(user);
    }

    public Long officeIdOf(Long userId) {
        return users.findById(userId)
                .map(user -> user.getOffice() == null ? null : user.getOffice().getId())
                .orElse(null);
    }

    private UserResponse toResponse(AppUser user) {
        List<String> roleNames = user.getRoles() == null
                ? List.of()
                : user.getRoles().stream().map(Role::getName).sorted(Comparator.naturalOrder()).toList();
        List<Long> roleIds = user.getRoles() == null
                ? List.of()
                : user.getRoles().stream().map(Role::getId).sorted(Comparator.naturalOrder()).toList();
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.isEnabled(),
                user.getOffice() == null ? null : user.getOffice().getId(),
                user.getOffice() == null ? null : user.getOffice().getName(),
                user.getOffice() == null ? null : user.getOffice().getHierarchy(),
                roleNames,
                roleIds,
                user.getCreatedBy(),
                user.getCreatedDate(),
                user.getLastModifiedBy(),
                user.getLastModifiedDate());
    }
}
