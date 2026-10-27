package com.nsure.security.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.audit.service.CommandHandler;
import com.nsure.audit.service.CommandRequest;
import com.nsure.audit.service.CommandResult;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.Permission;
import com.nsure.security.domain.Role;
import com.nsure.security.repository.PermissionRepository;
import com.nsure.security.repository.RoleRepository;
import com.nsure.security.service.SuperAdminGuard;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PermissionCommandHandler implements CommandHandler {

    private static final Set<String> PROTECTED = Set.of(
            "ALL_FUNCTIONS", "ALL_FUNCTIONS_READ", "CHECKER_SUPER_USER");

    private final PermissionRepository permissions;
    private final RoleRepository roles;
    private final SuperAdminGuard superAdmins;
    private final ObjectMapper mapper;

    public PermissionCommandHandler(
            PermissionRepository permissions,
            RoleRepository roles,
            SuperAdminGuard superAdmins,
            ObjectMapper mapper) {
        this.permissions = permissions;
        this.roles = roles;
        this.superAdmins = superAdmins;
        this.mapper = mapper;
    }

    @Override
    public String entityName() {
        return "PERMISSION";
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
        PermissionBody body = read(request.json());
        Permission permission = new Permission();
        apply(permission, body, true);
        permission = permissions.save(permission);
        return new CommandResult(permission.getId(), idJson(permission.getId()));
    }

    private CommandResult update(CommandRequest request) {
        Permission permission = permissions.findById(request.resourceId())
                .orElseThrow(() -> new NotFoundException("Permission not found"));
        if (PROTECTED.contains(permission.getCode().toUpperCase())) {
            throw new IllegalArgumentException("This permission cannot be changed");
        }
        apply(permission, read(request.json()), false);
        permissions.save(permission);
        return new CommandResult(permission.getId(), idJson(permission.getId()));
    }

    private CommandResult delete(CommandRequest request) {
        Permission permission = permissions.findById(request.resourceId())
                .orElseThrow(() -> new NotFoundException("Permission not found"));
        if (PROTECTED.contains(permission.getCode().toUpperCase())) {
            throw new IllegalArgumentException("This permission cannot be deleted");
        }
        for (Role role : roles.findAll()) {
            if (role.getPermissions() != null) {
                role.getPermissions().removeIf(assigned -> permission.getId().equals(assigned.getId()));
            }
        }
        permissions.delete(permission);
        return new CommandResult(permission.getId(), idJson(permission.getId()));
    }

    private void apply(Permission permission, PermissionBody body, boolean creating) {
        if (creating || body.code() != null) {
            String code = requiredToken(body.code(), "Permission code", 100);
            if (permissions.findByCodeIgnoreCase(code)
                    .filter(existing -> !existing.getId().equals(permission.getId()))
                    .isPresent()) {
                throw new IllegalArgumentException("A permission with this code already exists");
            }
            permission.setCode(code);
        }
        if (creating || body.grouping() != null) {
            permission.setGrouping(requiredToken(body.grouping(), "Group", 45));
        }
        if (body.entityName() != null) {
            permission.setEntityName(blankToNull(body.entityName(), "Entity", 100));
        }
        if (body.actionName() != null) {
            permission.setActionName(blankToNull(body.actionName(), "Action", 100));
        }
        if (body.functionName() != null) {
            permission.setFunctionName(blankToNull(body.functionName(), "Function", 150));
        }
        if (body.canMakerChecker() != null) {
            permission.setCanMakerChecker(body.canMakerChecker());
        }
    }

    private String requiredToken(String value, String label, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(label + " must be at most " + max + " characters");
        }
        return trimmed;
    }

    private String blankToNull(String value, String label, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requiredToken(value, label, max);
    }

    private PermissionBody read(String json) {
        try {
            return mapper.readValue(json == null || json.isBlank() ? "{}" : json, PermissionBody.class);
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

    private record PermissionBody(
            String grouping,
            String code,
            String entityName,
            String actionName,
            String functionName,
            Boolean canMakerChecker) {
    }
}
