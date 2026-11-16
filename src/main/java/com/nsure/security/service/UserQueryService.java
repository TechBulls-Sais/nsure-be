package com.nsure.security.service;

import com.nsure.common.exception.NotFoundException;
import com.nsure.security.api.UserResponse;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Role;
import com.nsure.security.repository.AppUserRepository;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserQueryService {

    private final AppUserRepository users;
    private final PlatformSecurityContext security;

    public UserQueryService(AppUserRepository users, PlatformSecurityContext security) {
        this.users = users;
        this.security = security;
    }

    public List<UserResponse> list() {
        AppUser caller = security.authenticatedUser();
        caller.validateHasReadPermission("USER");
        return users.findAll(Sort.by("username")).stream().map(this::toResponse).toList();
    }

    public UserResponse get(Long id) {
        AppUser caller = security.authenticatedUser();
        caller.validateHasReadPermission("USER");
        AppUser user = users.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        return toResponse(user);
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
                roleNames,
                roleIds,
                user.getCreatedBy(),
                user.getCreatedDate(),
                user.getLastModifiedBy(),
                user.getLastModifiedDate());
    }
}
