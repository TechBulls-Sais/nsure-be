package com.nsure.security.domain;

import com.nsure.audit.domain.AuditableEntity;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The signed-in principal. Users hold roles, roles hold permission codes; the spec's roles and grant matrix are data on
 * top of that one model. Access is deny by default (Nsure Life NS-SH11-002): a code is held only through an enabled
 * role that grants it or an active emergency grant (NS-SH11-003). There is no wildcard code and no role that passes
 * every check, so making, approving and administering stay separable duties.
 */
@Entity
@Table(name = "app_user")
public class AppUser extends AuditableEntity implements UserDetails {

    /** The administrator role. Only its holders change it or its membership; it grants nothing by name. */
    public static final String SUPER_ADMIN_ROLE = "Super Admin";

    /** The portal customer role (spec 04 §2, role 1). Protected: it never holds a permission (A-1, A-2). */
    public static final String PORTAL_ROLE = "Policyholder / End User";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(nullable = false, length = 255)
    private String password;

    @Column(nullable = false)
    private boolean enabled = true;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "app_user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    /** Emergency grants; an active one counts as a held permission while it lasts (NS-SH11-003). */
    @OneToMany(mappedBy = "user", fetch = FetchType.EAGER)
    private Set<EmergencyAccessGrant> emergencyGrants = new HashSet<>();

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (roles != null) {
            for (Role role : roles) {
                if (role.isDisabled() || role.getPermissions() == null) {
                    continue;
                }
                for (Permission permission : role.getPermissions()) {
                    authorities.add(new SimpleGrantedAuthority(permission.getCode()));
                }
            }
        }
        if (emergencyGrants != null) {
            for (EmergencyAccessGrant grant : emergencyGrants) {
                if (grant.isActive()) {
                    authorities.add(new SimpleGrantedAuthority(grant.getPermissionCode()));
                }
            }
        }
        return authorities;
    }

    public void validateHasReadPermission(String entity) {
        validateHasPermissionTo(PermissionCode.code("READ", entity));
    }

    public void validateHasCreatePermission(String entity) {
        validateHasPermissionTo(PermissionCode.code("CREATE", entity));
    }

    public void validateHasUpdatePermission(String entity) {
        validateHasPermissionTo(PermissionCode.code("UPDATE", entity));
    }

    public void validateHasDeletePermission(String entity) {
        validateHasPermissionTo(PermissionCode.code("DELETE", entity));
    }

    public void validateHasPermissionTo(String permissionCode) {
        if (!hasPermissionTo(permissionCode)) {
            throw new NoAuthorizationException("You do not have permission to do this (" + permissionCode + ").");
        }
    }

    /** At least one of the codes ({@code requireAny}, spec 04 §5.1). */
    public void validateHasAnyPermission(String... permissionCodes) {
        for (String code : permissionCodes) {
            if (hasPermissionTo(code)) {
                return;
            }
        }
        throw new NoAuthorizationException(
                "You do not have permission to do this (" + String.join(" or ", permissionCodes) + ").");
    }

    /**
     * Whether this user may perform {@code permissionCode}: deny by default. Held through an enabled role that grants
     * it, or through an active emergency grant. An unknown code is a programming error (spec 04 A-4).
     */
    public boolean hasPermissionTo(String permissionCode) {
        String code = PermissionCatalog.require(permissionCode);
        if (roles != null) {
            for (Role role : roles) {
                if (role.hasPermissionTo(code)) {
                    return true;
                }
            }
        }
        if (emergencyGrants != null) {
            for (EmergencyAccessGrant grant : emergencyGrants) {
                if (grant.isActive() && grant.getPermissionCode().equalsIgnoreCase(code)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Every code this user may perform now. */
    public SortedSet<String> effectivePermissions() {
        SortedSet<String> codes = new TreeSet<>();
        for (String code : PermissionCatalog.codes()) {
            if (hasPermissionTo(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    /** Keeps the in-memory side in step when a grant is created (the table side is the grant's user_id). */
    void attachEmergencyGrant(EmergencyAccessGrant grant) {
        if (emergencyGrants == null) {
            emergencyGrants = new HashSet<>();
        }
        emergencyGrants.add(grant);
    }

    /** The emergency grants active now. */
    public List<EmergencyAccessGrant> activeEmergencyGrants() {
        return emergencyGrants == null ? List.of()
                : emergencyGrants.stream().filter(EmergencyAccessGrant::isActive).toList();
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

    /**
     * Staff: holds at least one enabled role with desk access. The test is positive (spec 04 A-11): an account that is
     * not explicitly staff is a scoped portal customer, and an account with no role at all reaches nothing but the
     * portal.
     */
    public boolean isStaffUser() {
        if (roles == null) {
            return false;
        }
        for (Role role : roles) {
            if (!role.isDisabled() && role.isDeskAccess()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A partner portal login (agent, employer or lender): what its enabled roles grant is the partner portal and
     * nothing else. Its role has desk access, so it signs in like staff, but it is never the insurer's own staff (D-07).
     */
    public boolean isPartnerLogin() {
        boolean portal = false;
        if (roles != null) {
            for (Role role : roles) {
                if (role.isDisabled() || role.getPermissions() == null) {
                    continue;
                }
                for (Permission permission : role.getPermissions()) {
                    if (!permission.hasCode(PermissionCatalog.USE_PARTNER_PORTAL.name())) {
                        return false;
                    }
                    portal = true;
                }
            }
        }
        return portal;
    }

    /** A customer login: it may use the customer portal only, never the staff workspace or its API. */
    public boolean isPortalCustomer() {
        return !isStaffUser();
    }

    /**
     * Holds the administrator role. This decides only who may change that role and its holders; it never grants a
     * permission (every check is an explicit code).
     */
    public boolean isSuperAdmin() {
        return hasRole(SUPER_ADMIN_ROLE);
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
