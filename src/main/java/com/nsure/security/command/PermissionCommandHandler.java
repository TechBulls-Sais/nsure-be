package com.nsure.security.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.audit.service.CommandHandler;
import com.nsure.audit.service.CommandRequest;
import com.nsure.audit.service.CommandResult;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.Permission;
import com.nsure.security.repository.PermissionRepository;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Edits the presentation of a permission code: its label, its group and whether it needs a checker. The codes
 * themselves are a closed vocabulary that lives in source ({@link com.nsure.security.domain.PermissionCatalog},
 * spec 04 A-4), so they are never created, renamed or deleted through the API: a code nothing checks would grant
 * nothing, and deleting a code something checks would make it unreachable. {@code UPDATE_PERMISSION} is checked by
 * the command pipeline before this runs.
 */
@Component
public class PermissionCommandHandler implements CommandHandler {

    /**
     * Identity-administration codes whose four-eyes check cannot be switched off: otherwise an administrator holding
     * UPDATE_PERMISSION could drop the checker and then change roles or users alone (no self-approval, NS-UX-004).
     */
    private static final Set<String> FOUR_EYES_LOCKED = Set.of("UPDATE_USER", "UPDATE_ROLE", "PERMISSIONS_ROLE");

    private final PermissionRepository permissions;
    private final ObjectMapper mapper;

    public PermissionCommandHandler(PermissionRepository permissions, ObjectMapper mapper) {
        this.permissions = permissions;
        this.mapper = mapper;
    }

    @Override
    public String entityName() {
        return "PERMISSION";
    }

    @Override
    public Set<String> actions() {
        return Set.of("UPDATE");
    }

    @Override
    public CommandResult handle(CommandRequest request) {
        if (!"UPDATE".equals(request.actionName())) {
            throw new IllegalArgumentException("Permission codes are defined in source and cannot be "
                    + request.actionName().toLowerCase() + "d through the API.");
        }
        Permission permission = permissions.findById(request.resourceId())
                .orElseThrow(() -> new NotFoundException("Permission not found"));
        PermissionBody body = read(request.json());
        if (body.code() != null && !body.code().trim().equalsIgnoreCase(permission.getCode())) {
            throw new IllegalArgumentException("A permission code cannot be renamed; it is checked by name in source.");
        }
        if (body.grouping() != null) {
            permission.setGrouping(requiredToken(body.grouping(), "Group", 45));
        }
        if (body.functionName() != null) {
            permission.setFunctionName(blankToNull(body.functionName(), "Function", 150));
        }
        if (body.canMakerChecker() != null) {
            if (!body.canMakerChecker() && FOUR_EYES_LOCKED.contains(permission.getCode().toUpperCase())) {
                throw new IllegalArgumentException(permission.getCode()
                        + " always needs a second person to approve it; its checker cannot be switched off.");
            }
            permission.setCanMakerChecker(body.canMakerChecker());
        }
        permissions.save(permission);
        return new CommandResult(permission.getId(), idJson(permission.getId()));
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
