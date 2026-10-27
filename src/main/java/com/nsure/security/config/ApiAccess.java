package com.nsure.security.config;

import com.nsure.security.capability.Capability;
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
 * <p>Super Admin and Super user pass every rule (except that nothing is needed for {@code PUBLIC}). The check
 * runs before any permission lookup, so an empty or broken grant table can never lock them out.
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
        /** Super Admin or Super user only. */
        SUPER_ADMIN,
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

    public static ApiAccess superAdmin() {
        return new ApiAccess(Kind.SUPER_ADMIN, Set.of());
    }

    /**
     * Staff with any one of these codes. A code with a dot is a capability ({@code rate.read}) and must exist in
     * {@link Capability}; anything else is a function code ({@code READ_CLAIM}) from {@code app_permission}.
     */
    public static ApiAccess anyOf(String... codes) {
        if (codes.length == 0) {
            throw new IllegalArgumentException("A permission rule needs at least one permission code.");
        }
        Set<String> set = new LinkedHashSet<>();
        for (String code : codes) {
            if (code.contains(".")) {
                Capability.require(code);
            }
            set.add(code);
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
        if (user.isSuperAdmin()) {
            return true;
        }
        return switch (kind) {
            case AUTHENTICATED -> true;
            case CUSTOMER -> user.isPortalCustomer();
            case STAFF -> !user.isPortalCustomer();
            case SUPER_ADMIN -> false;
            case PERMISSION -> !user.isPortalCustomer() && holdsAny(user, authentication);
            case PUBLIC -> true;
        };
    }

    private boolean holdsAny(AppUser user, Authentication authentication) {
        for (String code : permissions) {
            if (hasAuthority(authentication, code) || (user != null && user.hasPermissionTo(code))) {
                return true;
            }
        }
        // ALL_FUNCTIONS_READ opens every read rule, as it does in the service-layer checks.
        boolean readRule = permissions.stream().anyMatch(code -> code.startsWith("READ_"));
        return readRule && (hasAuthority(authentication, "ALL_FUNCTIONS_READ")
                || (user != null && user.hasPermissionTo("ALL_FUNCTIONS_READ")));
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
