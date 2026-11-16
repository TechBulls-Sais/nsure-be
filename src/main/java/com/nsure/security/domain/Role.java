package com.nsure.security.domain;

import com.nsure.audit.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "app_role")
public class Role extends AuditableEntity {

    /**
     * Roles that may never hold a permission, desk access or a claim approval limit (spec 04 A-1). Hardcoded on
     * purpose: a configuration row would itself be editable, so it could not be the guard. A grant to the portal
     * role would hand that permission to every customer at once.
     */
    public static final java.util.Set<String> PROTECTED_ROLES = java.util.Set.of(
            "Policyholder / End User", "Guest", "All");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false)
    private boolean disabled;

    /** Spec 04 §2: a desk-access role makes its holder staff; without one a user is a scoped portal customer. */
    @Column(name = "desk_access", nullable = false)
    private boolean deskAccess = true;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "app_role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new HashSet<>();

    public boolean hasPermissionTo(String permissionCode) {
        if (disabled || permissions == null) {
            return false;
        }
        for (Permission permission : permissions) {
            if (permission.hasCode(permissionCode)) {
                return true;
            }
        }
        return false;
    }

    public boolean updatePermission(Permission permission, boolean selected) {
        if (permissions == null) {
            permissions = new HashSet<>();
        }
        return selected ? permissions.add(permission) : permissions.remove(permission);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public boolean isDeskAccess() {
        return deskAccess;
    }

    public void setDeskAccess(boolean deskAccess) {
        this.deskAccess = deskAccess;
    }

    public boolean isProtectedRole() {
        return name != null && PROTECTED_ROLES.stream().anyMatch(name::equalsIgnoreCase);
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        Role role = (Role) other;
        return id != null && id.equals(role.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
