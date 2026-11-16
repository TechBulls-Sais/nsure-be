package com.nsure.security.config;

import com.nsure.security.domain.PermissionCatalog;
import com.nsure.security.domain.AppUser;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Who may call one API endpoint. Every rule in {@link ApiAccessRules} is one of these.
 *
 * <p>Access is deny by default (Nsure Life NS-SH11-002): no role passes every rule by name, and there is no wildcard
 * code. The administrator reaches an endpoint the same way as everyone else, through an explicit grant.
 *
 * <p>A {@code PERMISSION} rule names the permission codes that open it; holding any one of them is enough.
 * Codes are what roles hold, never role names, so any role an administrator grants the code to (Claims
 * Assessor, Actuary, or a new custom role) gets the endpoint without a code change.
 */
public final class ApiAccess {

    public enum Kind {
        /** No login needed. */
        PUBLIC,
        /** Any signed-in user, staff or customer. */
        AUTHENTICATED,
        /** A portal customer (Policyholder); the service then limits them to their own records. */
        CUSTOMER,
        /** Any signed-in staff user, whatever their permissions. */
        STAFF,
        /** Staff holding at least one of the listed permission codes. */
        PERMISSION
    }

    private static final AuthenticationTrustResolver TRUST = new AuthenticationTrustResolverImpl();

    private final Kind kind;
    private final Set<String> permissions;

    private ApiAccess(Kind kind, Set<String> permissions) {
        this.kind = kind;
        this.permissions = permissions;
    }

    public static ApiAccess publicAccess() {
        return new ApiAccess(Kind.PUBLIC, Set.of());
    }

    public static ApiAccess authenticated() {
        return new ApiAccess(Kind.AUTHENTICATED, Set.of());
    }

    public static ApiAccess customer() {
        return new ApiAccess(Kind.CUSTOMER, Set.of());
    }

    public static ApiAccess staff() {
        return new ApiAccess(Kind.STAFF, Set.of());
    }

    /**
     * Staff with any one of these permission codes ({@code requireAny}, spec 04 §5.1). Each code must be in
     * {@link PermissionCatalog}: a typo fails when this class loads, not as a silent deny in production (A-4).
     */
    public static ApiAccess anyOf(String... codes) {
        if (codes.length == 0) {
            throw new IllegalArgumentException("A permission rule needs at least one permission code.");
        }
        Set<String> set = new LinkedHashSet<>();
        for (String code : codes) {
            set.add(PermissionCatalog.require(code));
        }
        return new ApiAccess(Kind.PERMISSION, Collections.unmodifiableSet(set));
    }

    public Kind kind() {
        return kind;
    }

    public Set<String> permissions() {
        return permissions;
    }

    public boolean allows(Authentication authentication) {
        if (kind == Kind.PUBLIC) {
            return true;
        }
        if (authentication == null || !TRUST.isAuthenticated(authentication)) {
            return false;
        }
        if (!(authentication.getPrincipal() instanceof AppUser user)) {
            // Not a database login (a test or token principal): only its granted authorities count.
            return kind == Kind.AUTHENTICATED || (kind == Kind.PERMISSION && holdsAny(null, authentication));
        }
        return switch (kind) {
            case AUTHENTICATED -> true;
            case CUSTOMER -> user.isPortalCustomer();
            case STAFF -> user.isStaffUser();
            case PERMISSION -> user.isStaffUser() && holdsAny(user, authentication);
            case PUBLIC -> true;
        };
    }

    private boolean holdsAny(AppUser user, Authentication authentication) {
        for (String code : permissions) {
            // A database login answers through the same check the services use (disabled roles, emergency grants),
            // so the URL rule and the service can never disagree.
            if (user != null ? user.hasPermissionTo(code) : hasAuthority(authentication, code)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAuthority(Authentication authentication, String code) {
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (code.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return kind == Kind.PERMISSION ? "any of " + List.copyOf(permissions) : kind.name();
    }
}
