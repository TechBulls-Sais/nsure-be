package com.nsure.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.audit.CommandHandler;
import com.nsure.audit.CommandJson;
import com.nsure.audit.CommandRequest;
import com.nsure.audit.CommandResult;
import com.nsure.security.domain.Permission;
import com.nsure.security.domain.Role;
import com.nsure.security.repo.PermissionRepository;
import com.nsure.security.repo.RoleRepository;
import com.nsure.web.ResourceNotFoundException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RoleCommandHandler implements CommandHandler {

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final ObjectMapper objectMapper;

    public RoleCommandHandler(RoleRepository roles, PermissionRepository permissions, ObjectMapper objectMapper) {
        this.roles = roles;
        this.permissions = permissions;
        this.objectMapper = objectMapper;
    }

    @Override
    public String entityName() {
        return "ROLE";
    }

    @Override
    public Set<String> actionNames() {
        return Set.of("CREATE", "UPDATE", "PERMISSIONS");
    }

    @Override
    public CommandResult handle(CommandRequest request) {
        JsonNode body = CommandJson.parse(request.json());
        return switch (request.actionName()) {
            case "CREATE" -> create(body);
            case "UPDATE" -> update(request.resourceId(), body);
            case "PERMISSIONS" -> updatePermissions(request.resourceId(), body);
            default -> throw new IllegalArgumentException("Unsupported action " + request.actionName());
        };
    }

    private CommandResult create(JsonNode body) {
        String name = roleName(CommandJson.requiredText(body, "name"));
        String description = description(CommandJson.requiredText(body, "description"));
        if (roles.existsByName(name)) {
            throw new IllegalArgumentException("Name is already in use");
        }
        Role role = roles.save(new Role(name, description));
        return result(role.getId());
    }

    private CommandResult update(Long id, JsonNode body) {
        Role role = load(id);
        String name = CommandJson.optionalText(body, "name");
        if (name != null) {
            String trimmed = roleName(name);
            roles.findByName(trimmed)
                    .filter(other -> !other.getId().equals(role.getId()))
                    .ifPresent(other -> {
                        throw new IllegalArgumentException("Name is already in use");
                    });
            role.setName(trimmed);
        }
        String description = CommandJson.optionalText(body, "description");
        if (description != null) {
            role.setDescription(description(description));
        }
        Boolean disabled = CommandJson.optionalBoolean(body, "disabled");
        if (disabled != null) {
            role.setDisabled(disabled);
        }
        roles.save(role);
        return result(role.getId());
    }

    private CommandResult updatePermissions(Long id, JsonNode body) {
        Role role = load(id);
        JsonNode selected = body.get("permissions");
        if (selected == null || !selected.isObject()) {
            throw new IllegalArgumentException("permissions object is required");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = selected.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getValue().isBoolean()) {
                throw new IllegalArgumentException("Permission " + entry.getKey() + " must be true or false");
            }
            Permission permission = permissions.findByCodeIgnoreCase(entry.getKey())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown permission: " + entry.getKey()));
            role.updatePermission(permission, entry.getValue().asBoolean());
        }
        roles.save(role);
        return result(role.getId());
    }

    private Role load(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("resource id is required");
        }
        return roles.findById(id).orElseThrow(() -> new ResourceNotFoundException("Role " + id + " not found"));
    }

    private String roleName(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw new IllegalArgumentException("name must be 1 to 100 characters");
        }
        return trimmed;
    }

    private String description(String description) {
        String trimmed = description.trim();
        if (trimmed.isEmpty() || trimmed.length() > 500) {
            throw new IllegalArgumentException("description must be 1 to 500 characters");
        }
        return trimmed;
    }

    private CommandResult result(Long id) {
        ObjectNode node = objectMapper.createObjectNode().put("resourceId", id);
        return new CommandResult(id, node.toString());
    }
}
