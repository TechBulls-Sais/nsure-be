package com.nsure.security.domain;

import com.nsure.audit.domain.AuditableEntity;
import com.nsure.security.capability.CapabilityGrant;
import com.nsure.security.capability.NsureRole;
import com.nsure.security.exception.NoAuthorizationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@Entity
@Table(name = "app_user")
public class AppUser extends AuditableEntity implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(nullable = false, length = 255)
    private String password;

    @Column(nullable = false)
    private boolean enabled = true;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "office_id")
    private Office office;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "app_user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "nsure_user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<NsureRole> capabilityRoles = new HashSet<>();

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (roles == null) {
            return authorities;
        }
        for (Role role : roles) {
            if (role.isDisabled()) {
                continue;
            }
            for (Permission permission : role.getPermissions()) {
                authorities.add(new SimpleGrantedAuthority(permission.getCode()));
            }
        }
        if (capabilityRoles != null) {
            for (NsureRole capabilityRole : capabilityRoles) {
                if (!capabilityRole.isEnabled() || capabilityRole.getGrants() == null) {
                    continue;
                }
                for (CapabilityGrant grant : capabilityRole.getGrants()) {
                    authorities.add(new SimpleGrantedAuthority(grant.getCapability()));
                }
            }
        }
        return authorities;
    }

    public void validateHasReadPermission(String entity) {
        validateHasPermission("READ", entity);
    }

    public void validateHasCreatePermission(String entity) {
        validateHasPermission("CREATE", entity);
    }

    public void validateHasUpdatePermission(String entity) {
        validateHasPermission("UPDATE", entity);
    }

    public void validateHasDeletePermission(String entity) {
        validateHasPermission("DELETE", entity);
    }

    public void validateHasPermissionTo(String permissionCode) {
        if (!hasPermissionTo(permissionCode)) {
            throw new NoAuthorizationException("User has no authority to: " + permissionCode);
        }
    }

    private void validateHasPermission(String action, String entity) {
        String code = PermissionCode.code(action, entity);
        if ("READ".equals(action)) {
            if (hasAny("ALL_FUNCTIONS", "ALL_FUNCTIONS_READ", code)) {
                return;
            }
        } else if (hasAny("ALL_FUNCTIONS", code)) {
            return;
        }
        throw new NoAuthorizationException("User has no authority to " + action + " " + entity);
    }

    public boolean hasPermissionTo(String permissionCode) {
        // Super Admin and Super user may perform every function, even if their role lost ALL_FUNCTIONS.
        if (isSuperAdmin() || hasAllFunctions()) {
            return true;
        }
        if (roles == null) {
            return false;
        }
        for (Role role : roles) {
            if (role.hasPermissionTo(permissionCode)) {
                return true;
            }
        }
        return false;
    }

    public void replaceRoles(Set<Role> assigned) {
        if (roles == null) {
            roles = new HashSet<>();
        }
        roles.clear();
        if (assigned != null) {
            roles.addAll(assigned);
        }
    }

    private boolean hasAllFunctions() {
        if (roles == null) {
            return false;
        }
        for (Role role : roles) {
            if (role.hasPermissionTo("ALL_FUNCTIONS")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAny(String... codes) {
        for (String code : codes) {
            if (hasPermissionTo(code)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Office getOffice() {
        return office;
    }

    public void setOffice(Office office) {
        this.office = office;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public boolean hasRole(String name) {
        if (roles == null || name == null) {
            return false;
        }
        for (Role role : roles) {
            if (!role.isDisabled() && name.equalsIgnoreCase(role.getName())) {
                return true;
            }
        }
        return false;
    }

    /** A customer login: it may use the customer portal only, never the staff workspace or its API. */
    public boolean isPortalCustomer() {
        return hasRole("Policyholder") && !isSuperAdmin();
    }

    public boolean isSuperAdmin() {
        if (roles == null) {
            return false;
        }
        for (Role role : roles) {
            if (!role.isDisabled()
                    && ("Super user".equalsIgnoreCase(role.getName())
                            || "Super Admin".equalsIgnoreCase(role.getName()))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        AppUser user = (AppUser) other;
        return id != null && id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
