package com.nsure.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.audit.CommandHandler;
import com.nsure.audit.CommandJson;
import com.nsure.audit.CommandJsonSanitizer;
import com.nsure.audit.CommandRequest;
import com.nsure.audit.CommandResult;
import com.nsure.audit.MakerCheckerPolicy;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Role;
import com.nsure.security.repo.AppUserRepository;
import com.nsure.security.repo.RoleRepository;
import com.nsure.web.ResourceNotFoundException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class UserCommandHandler implements CommandHandler {

    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,100}");
    private static final String SYSTEM_USERNAME = "system";

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final OfficeAccess officeAccess;
    private final PlatformSecurityContext security;
    private final MakerCheckerPolicy makerChecker;
    private final ObjectMapper objectMapper;

    public UserCommandHandler(AppUserRepository users,
                              RoleRepository roles,
                              PasswordEncoder passwordEncoder,
                              OfficeAccess officeAccess,
                              PlatformSecurityContext security,
                              MakerCheckerPolicy makerChecker,
                              ObjectMapper objectMapper) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.officeAccess = officeAccess;
        this.security = security;
        this.makerChecker = makerChecker;
        this.objectMapper = objectMapper;
    }

    @Override
    public String entityName() {
        return "USER";
    }

    @Override
    public Set<String> actionNames() {
        return Set.of("CREATE", "UPDATE");
    }

    @Override
    public CommandResult handle(CommandRequest request) {
        JsonNode body = CommandJson.parse(request.json());
        return switch (request.actionName()) {
            case "CREATE" -> create(body);
            case "UPDATE" -> update(request.resourceId(), body);
            default -> throw new IllegalArgumentException("Unsupported action " + request.actionName());
        };
    }

    private CommandResult create(JsonNode body) {
        AppUser actor = security.authenticatedUser();
        String username = normalizeUsername(CommandJson.requiredText(body, "username"));
        String password = CommandJson.requiredText(body, "password");
        validatePassword(password);
        if (users.existsByUsername(username)) {
            throw new IllegalArgumentException("Username is already in use");
        }
        Long officeId = CommandJson.requiredLong(body, "officeId");
        officeAccess.validateResourceOffice(officeId, actor);
        Boolean requestedEnabled = CommandJson.optionalBoolean(body, "enabled");
        boolean enabled = requestedEnabled == null || requestedEnabled;
        AppUser user = new AppUser(username, passwordEncoder.encode(password), enabled, officeId);
        user.replaceRoles(loadRoles(roleIds(body)));
        users.save(user);
        return result(user.getId());
    }

    private CommandResult update(Long id, JsonNode body) {
        if (id == null) {
            throw new IllegalArgumentException("resource id is required");
        }
        AppUser actor = security.authenticatedUser();
        AppUser user = users.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User " + id + " not found"));
        if (SYSTEM_USERNAME.equals(user.getUsername())) {
            throw new IllegalArgumentException("The system user cannot be modified");
        }
        officeAccess.validateResourceOffice(user.getOfficeId(), actor);

        if (body.hasNonNull("password")) {
            String password = body.get("password").asText();
            if (!CommandJsonSanitizer.REDACTED.equals(password)) {
                if (!body.get("password").isTextual()) {
                    throw new IllegalArgumentException("password must be a string");
                }
                if (makerChecker.requiresApproval(actor, "UPDATE_USER", id)) {
                    throw new IllegalArgumentException(
                            "Password cannot be changed while maker-checker is required for UPDATE_USER");
                }
                validatePassword(password);
                user.setPassword(passwordEncoder.encode(password));
            }
        }

        Boolean enabled = CommandJson.optionalBoolean(body, "enabled");
        if (enabled != null) {
            user.setEnabled(enabled);
        }
        Long officeId = CommandJson.optionalLong(body, "officeId");
        if (officeId != null && !officeId.equals(user.getOfficeId())) {
            officeAccess.validateResourceOffice(officeId, actor);
            user.setOfficeId(officeId);
        }
        if (body.has("roleIds")) {
            user.replaceRoles(loadRoles(roleIds(body)));
        }
        users.save(user);
        return result(user.getId());
    }

    private Set<Long> roleIds(JsonNode body) {
        if (!body.has("roleIds") || body.get("roleIds").isNull()) {
            return Set.of();
        }
        if (!body.get("roleIds").isArray()) {
            throw new IllegalArgumentException("roleIds must be an array");
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode node : body.get("roleIds")) {
            if (!node.isIntegralNumber()) {
                throw new IllegalArgumentException("roleIds must contain numeric ids");
            }
            ids.add(node.asLong());
        }
        return ids;
    }

    private Set<Role> loadRoles(Set<Long> ids) {
        if (ids.isEmpty()) {
            return new HashSet<>();
        }
        List<Role> found = roles.findAllById(ids);
        if (found.size() != ids.size()) {
            throw new IllegalArgumentException("Unknown role id");
        }
        return new HashSet<>(found);
    }

    private String normalizeUsername(String raw) {
        String username = raw.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "username must be 3 to 100 characters and use letters, digits, dot, underscore, or hyphen");
        }
        if (SYSTEM_USERNAME.equals(username)) {
            throw new IllegalArgumentException("username is reserved");
        }
        return username;
    }

    private void validatePassword(String password) {
        if (password.length() < 8 || password.length() > 72) {
            throw new IllegalArgumentException("password must be 8 to 72 characters");
        }
    }

    private CommandResult result(Long id) {
        ObjectNode node = objectMapper.createObjectNode().put("resourceId", id);
        return new CommandResult(id, node.toString());
    }
}
