package com.nsure.security.command;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.audit.service.CommandHandler;
import com.nsure.audit.service.CommandRequest;
import com.nsure.audit.service.CommandResult;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Permission;
import com.nsure.security.domain.Role;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.repository.AppUserRepository;
import com.nsure.security.repository.RoleRepository;
import com.nsure.security.service.PlatformSecurityContext;
import com.nsure.security.service.PortalCustomerLinks;
import com.nsure.security.session.SessionTokenService;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class UserCommandHandler implements CommandHandler {

    /** The stored form of a password in a request waiting for a checker (see {@link #prepare}). */
    private static final String PASSWORD_HASH = "passwordHash";

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PlatformSecurityContext security;
    private final PasswordEncoder passwordEncoder;
    private final PortalCustomerLinks portalLinks;
    private final ObjectMapper mapper;
    private final SessionTokenService sessions;

    public UserCommandHandler(
            AppUserRepository users,
            RoleRepository roles,
            PlatformSecurityContext security,
            PasswordEncoder passwordEncoder,
            PortalCustomerLinks portalLinks,
            ObjectMapper mapper,
            SessionTokenService sessions) {
        this.sessions = sessions;
        this.users = users;
        this.roles = roles;
        this.security = security;
        this.passwordEncoder = passwordEncoder;
        this.portalLinks = portalLinks;
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

    /**
     * D-03 (NS-SH11-003, NS-UX-004): creating or deleting a login that has access waits for a second person, like any
     * change to a user's roles, so one administrator cannot mint a second identity to approve their own changes. A
     * login with no access (no role, or only roles with no permission and no desk access) needs no checker.
     */
    @Override
    public boolean needsChecker(CommandRequest request) {
        return switch (request.actionName()) {
            case "CREATE" -> {
                UserBody body = read(request.json());
                yield body.roleIds() != null && loadRoles(body.roleIds()).stream().anyMatch(UserCommandHandler::givesAccess);
            }
            case "DELETE" -> users.findById(request.resourceId())
                    .map(user -> user.getRoles() != null && user.getRoles().stream().anyMatch(UserCommandHandler::givesAccess))
                    .orElse(true);
            default -> true;
        };
    }

    /**
     * A request waiting for a checker is stored until it is approved, so its password is stored as the hash it would
     * get anyway, never in plain text. Clients send {@code password}; a {@code passwordHash} only ever comes from here.
     */
    @Override
    public String prepare(CommandRequest request, boolean awaitingChecker) {
        JsonNode tree;
        try {
            tree = mapper.readTree(request.json() == null || request.json().isBlank() ? "{}" : request.json());
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Request body is not valid JSON");
        }
        if (!(tree instanceof ObjectNode body)) {
            return request.json();
        }
        if (body.has(PASSWORD_HASH)) {
            throw new IllegalArgumentException("Send the password, not a password hash");
        }
        JsonNode password = body.get("password");
        if (!awaitingChecker || password == null || !password.isTextual() || password.asText().isBlank()) {
            return request.json();
        }
        body.remove("password");
        body.put(PASSWORD_HASH, passwordEncoder.encode(requiredPassword(password.asText())));
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A role that gives its holder access: desk access, any permission, or the Super Admin role (enabled or not). */
    private static boolean givesAccess(Role role) {
        return role.isDeskAccess()
                || (role.getPermissions() != null && !role.getPermissions().isEmpty())
                || AppUser.SUPER_ADMIN_ROLE.equalsIgnoreCase(role.getName());
    }

    @Override
    public CommandResult handle(CommandRequest request) {
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
        user.setPassword(body.passwordHash() != null ? body.passwordHash()
                : passwordEncoder.encode(requiredPassword(body.password())));
        user.setEnabled(body.enabled() == null || body.enabled());
        if (body.roleIds() != null) {
            Set<Role> assigned = loadRoles(body.roleIds());
            assertMayGrant(assigned);
            assertPortalScope(username, assigned);
            user.replaceRoles(assigned);
        }
        user = users.save(user);
        return new CommandResult(user.getId(), idJson(user.getId()));
    }

    private CommandResult update(CommandRequest request) {
        AppUser user = users.findById(request.resourceId())
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertMayManage(user);
        UserBody body = read(request.json());
        if (body.username() != null) {
            String username = requiredUsername(body.username());
            if (isReserved(user.getUsername()) && !user.getUsername().equalsIgnoreCase(username)) {
                throw new IllegalArgumentException("This account cannot be renamed");
            }
            user.setUsername(username);
        }
        if (body.passwordHash() != null) {
            user.setPassword(body.passwordHash());
        } else if (body.password() != null && !body.password().isBlank()) {
            user.setPassword(passwordEncoder.encode(requiredPassword(body.password())));
        }
        if (body.enabled() != null) {
            if (isReserved(user.getUsername()) && !body.enabled()) {
                throw new IllegalArgumentException("This account cannot be disabled");
            }
            user.setEnabled(body.enabled());
        }
        if (body.roleIds() != null) {
            Set<Role> assigned = loadRoles(body.roleIds());
            assertMayGrant(assigned);
            assertPortalScope(user.getUsername(), assigned);
            user.replaceRoles(assigned);
            if ("admin".equalsIgnoreCase(user.getUsername()) && !user.isSuperAdmin()) {
                throw new IllegalArgumentException("The admin account must keep the Super Admin role");
            }
        }
        users.save(user);
        return new CommandResult(user.getId(), idJson(user.getId()));
    }

    private CommandResult delete(CommandRequest request) {
        AppUser user = users.findById(request.resourceId())
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertMayManage(user);
        if (isReserved(user.getUsername())) {
            throw new IllegalArgumentException("This account cannot be deleted");
        }
        if (user.getRoles() != null) {
            user.getRoles().clear();
        }
        // Their browser sessions go with them; the rows would otherwise block the delete.
        sessions.forget(user);
        users.delete(user);
        return new CommandResult(user.getId(), idJson(user.getId()));
    }

    private Set<Role> loadRoles(Set<Long> roleIds) {
        Set<Role> assigned = new HashSet<>();
        for (Long roleId : roleIds) {
            assigned.add(roles.findById(roleId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown role " + roleId)));
        }
        return assigned;
    }

    /** A super admin account is managed only by another super admin: UPDATE_USER alone cannot reset its password. */
    private void assertMayManage(AppUser target) {
        if (target.isSuperAdmin() && !security.authenticatedUser().isSuperAdmin()) {
            throw new NoAuthorizationException("Only a super admin can change a super admin account.");
        }
    }

    /**
     * Spec 04 A-19: a login linked to a customer record is a portal customer. It may hold only roles with no
     * permission and no desk access, or it would carry staff powers into the portal (or stop being scoped).
     */
    private void assertPortalScope(String username, Set<Role> assigned) {
        if (username == null || !portalLinks.isLinked(username)) {
            return;
        }
        for (Role role : assigned) {
            boolean armed = role.isDeskAccess() || (role.getPermissions() != null && !role.getPermissions().isEmpty());
            if (armed || AppUser.SUPER_ADMIN_ROLE.equalsIgnoreCase(role.getName())) {
                throw new IllegalArgumentException(username + " is linked to a customer record, so it cannot hold "
                        + role.getName() + ": that role has permissions or desk access.");
            }
        }
    }

    private void assertMayGrant(Set<Role> assigned) {
        AppUser caller = security.authenticatedUser();
        for (Role role : assigned) {
            if (AppUser.SUPER_ADMIN_ROLE.equalsIgnoreCase(role.getName()) && !caller.isSuperAdmin()) {
                throw new NoAuthorizationException("Only a super admin can assign the Super Admin role.");
            }
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserBody(String username, String password, String passwordHash, Boolean enabled,
            Set<Long> roleIds) {
    }
}
