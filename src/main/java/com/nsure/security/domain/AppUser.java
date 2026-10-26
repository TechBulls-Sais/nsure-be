package com.nsure.security.domain;

import com.nsure.audit.AuditableEntity;
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
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
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

    @Column(name = "office_id")
    private Long officeId;

    @Column(name = "customer_ref", length = 20)
    private String customerRef;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "app_user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new LinkedHashSet<>();

    protected AppUser() {
    }

    public AppUser(String username, String password, boolean enabled, Long officeId) {
        this.username = username;
        this.password = password;
        this.enabled = enabled;
        this.officeId = officeId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (Role role : roles) {
            if (role.isDisabled()) {
                continue;
            }
            for (Permission permission : role.getPermissions()) {
                if (permission.getCode() != null) {
                    authorities.add(new SimpleGrantedAuthority(permission.getCode()));
                }
            }
        }
        return List.copyOf(authorities);
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
        String permissionCode = PermissionCodes.code(action, entity);
        if ("READ".equals(action)) {
            if (hasAny(PermissionCodes.ALL_FUNCTIONS, PermissionCodes.ALL_FUNCTIONS_READ, permissionCode)) {
                return;
            }
        } else if (hasAny(PermissionCodes.ALL_FUNCTIONS, permissionCode)) {
            return;
        }
        throw new NoAuthorizationException("User has no authority to " + action + " " + entity);
    }

    public boolean hasPermissionTo(String permissionCode) {
        if (hasAllFunctions()) {
            return true;
        }
        for (Role role : roles) {
            if (role.hasPermissionTo(permissionCode)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAllFunctions() {
        for (Role role : roles) {
            if (role.hasPermissionTo(PermissionCodes.ALL_FUNCTIONS)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAny(String... codes) {
        for (String permissionCode : codes) {
            if (hasPermissionTo(permissionCode)) {
                return true;
            }
        }
        return false;
    }

    public void addRole(Role role) {
        roles.add(role);
    }

    public void replaceRoles(Set<Role> assigned) {
        roles.clear();
        if (assigned != null) {
            roles.addAll(assigned);
        }
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

    public Long getOfficeId() {
        return officeId;
    }

    public void setOfficeId(Long officeId) {
        this.officeId = officeId;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public void setCustomerRef(String customerRef) {
        this.customerRef = customerRef;
    }

    public boolean isSuperAdmin() {
        for (Role role : roles) {
            if (!role.isDisabled() && "Super user".equals(role.getName())) {
                return true;
            }
        }
        return false;
    }

    public boolean isCustomerPortal() {
        if (isSuperAdmin()) {
            return false;
        }
        for (Role role : roles) {
            if (!role.isDisabled() && role.getName() != null
                    && role.getName().startsWith("Policyholder")) {
                return true;
            }
        }
        return false;
    }
}
