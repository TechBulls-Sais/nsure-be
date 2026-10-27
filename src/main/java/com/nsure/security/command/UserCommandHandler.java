package com.nsure.security.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.audit.service.CommandHandler;
import com.nsure.audit.service.CommandRequest;
import com.nsure.audit.service.CommandResult;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Office;
import com.nsure.security.domain.Permission;
import com.nsure.security.domain.Role;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.repository.AppUserRepository;
import com.nsure.security.repository.OfficeRepository;
import com.nsure.security.repository.RoleRepository;
import com.nsure.security.service.OfficeAccessValidator;
import com.nsure.security.service.PlatformSecurityContext;
import com.nsure.security.service.SuperAdminGuard;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class UserCommandHandler implements CommandHandler {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final OfficeRepository offices;
    private final PlatformSecurityContext security;
    private final OfficeAccessValidator access;
    private final PasswordEncoder passwordEncoder;
    private final SuperAdminGuard superAdmins;
    private final ObjectMapper mapper;

    public UserCommandHandler(
            AppUserRepository users,
            RoleRepository roles,
            OfficeRepository offices,
            PlatformSecurityContext security,
            OfficeAccessValidator access,
            PasswordEncoder passwordEncoder,
            SuperAdminGuard superAdmins,
            ObjectMapper mapper) {
        this.users = users;
        this.roles = roles;
        this.offices = offices;
        this.security = security;
        this.access = access;
        this.passwordEncoder = passwordEncoder;
        this.superAdmins = superAdmins;
        this.mapper = mapper;
    }

    @Override
    public String entityName() {
        return "USER";
    }

    @Override
    public Set<String> actions() {
        return Set.of("CREATE", "UPDATE", "DELETE");
    }

    @Override
    public CommandResult handle(CommandRequest request) {
        superAdmins.require();
        return switch (request.actionName()) {
            case "CREATE" -> create(request);
            case "UPDATE" -> update(request);
            case "DELETE" -> delete(request);
            default -> throw new IllegalArgumentException("Unsupported action " + request.actionName());
        };
    }

    private CommandResult create(CommandRequest request) {
        UserBody body = read(request.json());
        String username = requiredUsername(body.username());
        if (isReserved(username)) {
            throw new IllegalArgumentException("This username is reserved");
        }
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(requiredPassword(body.password())));
        user.setEnabled(body.enabled() == null || body.enabled());
        user.setOffice(resolveOffice(body.officeId(), true));
        if (body.roleIds() != null) {
            Set<Role> assigned = loadRoles(body.roleIds());
            assertMayGrant(assigned);
            user.replaceRoles(assigned);
        }
        user = users.save(user);
        return new CommandResult(user.getId(), idJson(user.getId()));
    }

    private CommandResult update(CommandRequest request) {
        AppUser user = users.findById(request.resourceId())
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (user.getOffice() != null) {
            access.validateAccess(user.getOffice().getHierarchy(), access.hierarchyOf(security.authenticatedUser()));
        }
        UserBody body = read(request.json());
        if (body.username() != null) {
            String username = requiredUsername(body.username());
            if (isReserved(user.getUsername()) && !user.getUsername().equalsIgnoreCase(username)) {
                throw new IllegalArgumentException("This account cannot be renamed");
            }
            user.setUsername(username);
        }
        if (body.password() != null && !body.password().isBlank()) {
            user.setPassword(passwordEncoder.encode(requiredPassword(body.password())));
        }
        if (body.enabled() != null) {
            if (isReserved(user.getUsername()) && !body.enabled()) {
                throw new IllegalArgumentException("This account cannot be disabled");
            }
            user.setEnabled(body.enabled());
        }
        if (body.officeId() != null) {
            user.setOffice(resolveOffice(body.officeId(), true));
        }
        if (body.roleIds() != null) {
            Set<Role> assigned = loadRoles(body.roleIds());
            assertMayGrant(assigned);
            user.replaceRoles(assigned);
            if ("admin".equalsIgnoreCase(user.getUsername()) && !user.hasPermissionTo("ALL_FUNCTIONS")) {
                throw new IllegalArgumentException("The admin account must keep ALL_FUNCTIONS");
            }
        }
        users.save(user);
        return new CommandResult(user.getId(), idJson(user.getId()));
    }

    private CommandResult delete(CommandRequest request) {
        AppUser user = users.findById(request.resourceId())
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (isReserved(user.getUsername())) {
            throw new IllegalArgumentException("This account cannot be deleted");
        }
        if (user.getRoles() != null) {
            user.getRoles().clear();
        }
        users.delete(user);
        return new CommandResult(user.getId(), idJson(user.getId()));
    }

    private Office resolveOffice(Long officeId, boolean required) {
        if (officeId == null) {
            if (required) {
                throw new IllegalArgumentException("officeId is required");
            }
            return null;
        }
        Office office = offices.findById(officeId)
                .orElseThrow(() -> new IllegalArgumentException("Office not found"));
        access.validateAccess(office.getHierarchy(), access.hierarchyOf(security.authenticatedUser()));
        return office;
    }

    private Set<Role> loadRoles(Set<Long> roleIds) {
        Set<Role> assigned = new HashSet<>();
        for (Long roleId : roleIds) {
            assigned.add(roles.findById(roleId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown role " + roleId)));
        }
        return assigned;
    }

    private void assertMayGrant(Set<Role> assigned) {
        AppUser caller = security.authenticatedUser();
        for (Role role : assigned) {
            if (role.isDisabled() || role.getPermissions() == null) {
                continue;
            }
            for (Permission permission : role.getPermissions()) {
                if (!caller.hasPermissionTo(permission.getCode())) {
                    throw new NoAuthorizationException("User has no authority to grant " + permission.getCode());
                }
            }
        }
    }

    private String requiredUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        String trimmed = username.trim();
        if (trimmed.length() > 100) {
            throw new IllegalArgumentException("Username must be at most 100 characters");
        }
        return trimmed;
    }

    private String requiredPassword(String password) {
        if (password == null || password.isBlank() || "***".equals(password)) {
            throw new IllegalArgumentException("Password is required");
        }
        if (password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        return password;
    }

    private boolean isReserved(String username) {
        return "admin".equalsIgnoreCase(username) || "system".equalsIgnoreCase(username);
    }

    private UserBody read(String json) {
        try {
            return mapper.readValue(json == null || json.isBlank() ? "{}" : json, UserBody.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Request body is not valid JSON");
        }
    }

    private String idJson(Long id) {
        try {
            return mapper.writeValueAsString(Map.of("id", id));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record UserBody(String username, String password, Boolean enabled, Long officeId, Set<Long> roleIds) {
    }
}
