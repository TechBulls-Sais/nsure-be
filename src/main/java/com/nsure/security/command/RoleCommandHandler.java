package com.nsure.security.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.audit.service.CommandHandler;
import com.nsure.audit.service.CommandRequest;
import com.nsure.audit.service.CommandResult;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Permission;
import com.nsure.security.domain.Role;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.repository.AppUserRepository;
import com.nsure.security.repository.PermissionRepository;
import com.nsure.security.repository.RoleRepository;
import com.nsure.security.service.PlatformSecurityContext;
import com.nsure.security.service.SuperAdminGuard;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RoleCommandHandler implements CommandHandler {

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final AppUserRepository users;
    private final PlatformSecurityContext security;
    private final SuperAdminGuard superAdmins;
    private final ObjectMapper mapper;

    public RoleCommandHandler(
            RoleRepository roles,
            PermissionRepository permissions,
            AppUserRepository users,
            PlatformSecurityContext security,
            SuperAdminGuard superAdmins,
            ObjectMapper mapper) {
        this.roles = roles;
        this.permissions = permissions;
        this.users = users;
        this.security = security;
        this.superAdmins = superAdmins;
        this.mapper = mapper;
    }

    @Override
    public String entityName() {
        return "ROLE";
    }

    @Override
    public Set<String> actions() {
        return Set.of("CREATE", "UPDATE", "DELETE", "PERMISSIONS");
    }

    @Override
    public CommandResult handle(CommandRequest request) {
        superAdmins.require();
        return switch (request.actionName()) {
            case "CREATE" -> create(request);
            case "UPDATE" -> update(request);
            case "DELETE" -> delete(request);
            case "PERMISSIONS" -> updatePermissions(request);
            default -> throw new IllegalArgumentException("Unsupported action " + request.actionName());
        };
    }

    private CommandResult create(CommandRequest request) {
        RoleBody body = read(request.json(), RoleBody.class);
        String name = requiredText(body.name(), "Role name", 100);
        String description = requiredText(body.description(), "Role description", 500);
        Role role = new Role();
        role.setName(name);
        role.setDescription(description);
        role.setDisabled(Boolean.TRUE.equals(body.disabled()));
        role = roles.save(role);
        return new CommandResult(role.getId(), idJson(role.getId()));
    }

    private CommandResult update(CommandRequest request) {
        Role role = roles.findById(request.resourceId())
                .orElseThrow(() -> new NotFoundException("Role not found"));
        RoleBody body = read(request.json(), RoleBody.class);
        boolean superUser = "Super user".equalsIgnoreCase(role.getName());
        if (body.name() != null) {
            String name = requiredText(body.name(), "Role name", 100);
            if (superUser && !"Super user".equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("The Super user role cannot be renamed");
            }
            role.setName(name);
        }
        if (body.description() != null) {
            role.setDescription(requiredText(body.description(), "Role description", 500));
        }
        if (body.disabled() != null) {
            if (superUser && body.disabled()) {
                throw new IllegalArgumentException("The Super user role cannot be disabled");
            }
            role.setDisabled(body.disabled());
        }
        roles.save(role);
        return new CommandResult(role.getId(), idJson(role.getId()));
    }

    private CommandResult delete(CommandRequest request) {
        Role role = roles.findById(request.resourceId())
                .orElseThrow(() -> new NotFoundException("Role not found"));
        if ("Super user".equalsIgnoreCase(role.getName())) {
            throw new IllegalArgumentException("The Super user role cannot be deleted");
        }
        for (AppUser user : users.findAll()) {
            if (user.getRoles() != null) {
                user.getRoles().removeIf(assigned -> role.getId().equals(assigned.getId()));
            }
        }
        if (role.getPermissions() != null) {
            role.getPermissions().clear();
        }
        roles.delete(role);
        return new CommandResult(role.getId(), idJson(role.getId()));
    }

    private CommandResult updatePermissions(CommandRequest request) {
        Role role = roles.findById(request.resourceId())
                .orElseThrow(() -> new NotFoundException("Role not found"));
        PermissionBody body = read(request.json(), PermissionBody.class);
        if (body.permissions() == null) {
            throw new IllegalArgumentException("permissions is required");
        }
        AppUser caller = security.authenticatedUser();
        boolean superUser = "Super user".equalsIgnoreCase(role.getName());
        for (Map.Entry<String, Boolean> entry : body.permissions().entrySet()) {
            Permission permission = permissions.findByCodeIgnoreCase(entry.getKey())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown permission " + entry.getKey()));
            boolean selected = Boolean.TRUE.equals(entry.getValue());
            if (superUser && permission.hasCode("ALL_FUNCTIONS") && !selected) {
                throw new IllegalArgumentException("The Super user role must keep ALL_FUNCTIONS");
            }
            if (selected && !caller.hasPermissionTo(permission.getCode())) {
                throw new NoAuthorizationException("User has no authority to grant " + permission.getCode());
            }
            role.updatePermission(permission, selected);
        }
        roles.save(role);
        return new CommandResult(role.getId(), idJson(role.getId()));
    }

    private String requiredText(String value, String label, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(label + " must be at most " + max + " characters");
        }
        return trimmed;
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json == null || json.isBlank() ? "{}" : json, type);
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

    private record RoleBody(String name, String description, Boolean disabled) {
    }

    private record PermissionBody(Map<String, Boolean> permissions) {
    }
}
