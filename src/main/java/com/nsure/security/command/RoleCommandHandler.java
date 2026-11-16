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
import com.nsure.security.service.PortalCustomerLinks;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Creates, edits and deletes roles and sets their permissions. The command pipeline has already checked
 * {@code CREATE_ROLE}, {@code UPDATE_ROLE}, {@code DELETE_ROLE} or {@code PERMISSIONS_ROLE}; the guards below run on
 * every path that changes a role (spec 04 §8), so no client can bypass them:
 *
 * <ul>
 *   <li>A protected role (Policyholder / End User, Guest, All) never holds a permission or desk access, and cannot be
 *       renamed or deleted (A-1).</li>
 *   <li>A role held by any user linked to a customer record cannot be armed with permissions or desk access (A-19):
 *       otherwise "create an empty role, give it to a customer, arm it later" bypasses A-1.</li>
 *   <li>Nobody grants a code they do not hold themselves.</li>
 *   <li>Only a super admin changes the Super Admin role, which keeps its name. It holds explicit grants only; there is
 *       no wildcard code (Nsure Life NS-SH11-002).</li>
 *   <li>Disabling is a flag; assignments are kept (04 §9.1).</li>
 * </ul>
 */
@Component
public class RoleCommandHandler implements CommandHandler {

    /** Codes the Super Admin role keeps, so a mistaken edit cannot lock every administrator out of role grants. */
    private static final Set<String> ADMIN_LIFELINE = Set.of("PERMISSIONS_ROLE", "CHECKER_PERMISSIONS_ROLE");

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final AppUserRepository users;
    private final PlatformSecurityContext security;
    private final PortalCustomerLinks portalLinks;
    private final ObjectMapper mapper;

    public RoleCommandHandler(
            RoleRepository roles,
            PermissionRepository permissions,
            AppUserRepository users,
            PlatformSecurityContext security,
            PortalCustomerLinks portalLinks,
            ObjectMapper mapper) {
        this.roles = roles;
        this.permissions = permissions;
        this.users = users;
        this.security = security;
        this.portalLinks = portalLinks;
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
        assertNameFree(name, null);
        Role role = new Role();
        role.setName(name);
        role.setDescription(requiredText(body.description(), "Role description", 500));
        role.setDisabled(Boolean.TRUE.equals(body.disabled()));
        if (AppUser.SUPER_ADMIN_ROLE.equalsIgnoreCase(name)) {
            throw new IllegalArgumentException("There is exactly one Super Admin role");
        }
        role.setDeskAccess(!role.isProtectedRole() && (body.deskAccess() == null || body.deskAccess()));
        role = roles.save(role);
        return new CommandResult(role.getId(), idJson(role.getId()));
    }

    private CommandResult update(CommandRequest request) {
        Role role = role(request.resourceId());
        assertMayChange(role);
        RoleBody body = read(request.json(), RoleBody.class);
        boolean superRole = isSuperAdminRole(role);
        if (body.name() != null) {
            String name = requiredText(body.name(), "Role name", 100);
            if (!name.equalsIgnoreCase(role.getName())) {
                if (superRole) {
                    throw new IllegalArgumentException("The Super Admin role cannot be renamed");
                }
                if (role.isProtectedRole()) {
                    throw new IllegalArgumentException(role.getName()
                            + " is a protected role and cannot be renamed: customer sign-up and scoping depend on it.");
                }
                if (Role.PROTECTED_ROLES.stream().anyMatch(name::equalsIgnoreCase)
                        || AppUser.SUPER_ADMIN_ROLE.equalsIgnoreCase(name)) {
                    throw new IllegalArgumentException("The name " + name + " is reserved");
                }
                assertNameFree(name, role.getId());
            }
            role.setName(name);
        }
        if (body.description() != null) {
            role.setDescription(requiredText(body.description(), "Role description", 500));
        }
        if (body.disabled() != null) {
            if (superRole && body.disabled()) {
                throw new IllegalArgumentException("The Super Admin role cannot be disabled");
            }
            if (role.isProtectedRole() && body.disabled()) {
                throw new IllegalArgumentException(role.getName() + " is a protected role and cannot be disabled");
            }
            role.setDisabled(body.disabled());
        }
        if (body.deskAccess() != null && body.deskAccess() != role.isDeskAccess()) {
            if (body.deskAccess()) {
                assertMayArm(role, "desk access");
            } else if (superRole) {
                throw new IllegalArgumentException("The Super Admin role keeps desk access");
            }
            role.setDeskAccess(body.deskAccess());
        }
        roles.save(role);
        return new CommandResult(role.getId(), idJson(role.getId()));
    }

    private CommandResult delete(CommandRequest request) {
        Role role = role(request.resourceId());
        if (isSuperAdminRole(role)) {
            throw new IllegalArgumentException("The Super Admin role cannot be deleted");
        }
        if (role.isProtectedRole()) {
            throw new IllegalArgumentException(role.getName() + " is a protected role and cannot be deleted");
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
        Role role = role(request.resourceId());
        assertMayChange(role);
        PermissionBody body = read(request.json(), PermissionBody.class);
        if (body.permissions() == null) {
            throw new IllegalArgumentException("permissions is required");
        }
        AppUser caller = security.authenticatedUser();
        boolean superRole = isSuperAdminRole(role);
        boolean arming = body.permissions().values().stream().anyMatch(Boolean.TRUE::equals);
        if (arming) {
            assertMayArm(role, "permissions");
        }
        for (Map.Entry<String, Boolean> entry : body.permissions().entrySet()) {
            Permission permission = permissions.findByCodeIgnoreCase(entry.getKey())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown permission " + entry.getKey()));
            boolean selected = Boolean.TRUE.equals(entry.getValue());
            if (superRole && !selected && ADMIN_LIFELINE.contains(permission.getCode().toUpperCase())) {
                throw new IllegalArgumentException("The Super Admin role must keep " + permission.getCode()
                        + " so administrators can always restore grants (with a second administrator approving).");
            }
            if (selected && !caller.hasPermissionTo(permission.getCode())) {
                throw new NoAuthorizationException("You cannot grant " + permission.getCode()
                        + " because you do not hold it yourself.");
            }
            role.updatePermission(permission, selected);
        }
        roles.save(role);
        return new CommandResult(role.getId(), idJson(role.getId()));
    }

    /** A-1 and A-19: no permission or desk access for a protected role or a role a portal customer holds. */
    private void assertMayArm(Role role, String what) {
        if (role.isProtectedRole()) {
            throw new IllegalArgumentException(role.getName()
                    + " cannot be granted " + what + " — every portal customer holds it.");
        }
        long holders = portalLinks.linkedHoldersOf(role.getId());
        if (holders > 0) {
            throw new IllegalArgumentException(role.getName() + " is held by " + holders
                    + " portal customer(s), so it cannot be granted " + what + ". Remove it from them first.");
        }
    }

    /** Only a super admin may change the Super Admin role. */
    private void assertMayChange(Role role) {
        if (isSuperAdminRole(role) && !security.authenticatedUser().isSuperAdmin()) {
            throw new NoAuthorizationException("Only a super admin can change the Super Admin role.");
        }
    }

    private void assertNameFree(String name, Long ownId) {
        roles.findAll().stream()
                .filter(existing -> existing.getName().equalsIgnoreCase(name))
                .filter(existing -> !existing.getId().equals(ownId))
                .findAny()
                .ifPresent(existing -> {
                    throw new com.nsure.common.exception.ConflictException("A role named " + name + " already exists");
                });
    }

    private Role role(Long id) {
        return roles.findById(id).orElseThrow(() -> new NotFoundException("Role not found"));
    }

    private static boolean isSuperAdminRole(Role role) {
        return AppUser.SUPER_ADMIN_ROLE.equalsIgnoreCase(role.getName());
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

    private record RoleBody(String name, String description, Boolean disabled, Boolean deskAccess) {
    }

    private record PermissionBody(Map<String, Boolean> permissions) {
    }
}
