package com.nsure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Permission;
import com.nsure.security.domain.Role;
import com.nsure.security.exception.NoAuthorizationException;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

class AppUserPermissionTest {

    @Test
    void specificPermissionAllowsOnlyThatAction() {
        AppUser user = userWith(role("Clerk", permission("CREATE_CLIENT")));

        user.validateHasCreatePermission("CLIENT");
        assertThat(user.hasPermissionTo("create_client")).isTrue();
        assertThatThrownBy(() -> user.validateHasUpdatePermission("CLIENT"))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("User has no authority to UPDATE CLIENT");
    }

    @Test
    void allFunctionsAllowsWritesAndReads() {
        AppUser user = userWith(role("Super", permission("ALL_FUNCTIONS")));

        user.validateHasUpdatePermission("CLIENT");
        user.validateHasReadPermission("AUDIT");
        assertThat(user.hasPermissionTo("CHECKER_SUPER_USER")).isTrue();
    }

    @Test
    void allFunctionsReadDoesNotAllowWrites() {
        AppUser user = userWith(role("Reader", permission("ALL_FUNCTIONS_READ")));

        user.validateHasReadPermission("CLIENT");
        assertThatThrownBy(() -> user.validateHasUpdatePermission("CLIENT"))
                .isInstanceOf(NoAuthorizationException.class);
        assertThat(user.hasPermissionTo("UPDATE_CLIENT")).isFalse();
    }

    @Test
    void disabledRoleGrantsNothing() {
        Role role = role("Suspended", permission("CREATE_ROLE"));
        role.setDisabled(true);
        AppUser user = userWith(role);

        assertThat(user.getAuthorities()).isEmpty();
        assertThatThrownBy(() -> user.validateHasPermissionTo("CREATE_ROLE"))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("User has no authority to: CREATE_ROLE");
    }

    @Test
    void authoritiesArePermissionCodesFromEnabledRoles() {
        AppUser user = userWith(role("Clerk", permission("READ_CLIENT"), permission("UPDATE_CLIENT")));

        assertThat(user.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("READ_CLIENT", "UPDATE_CLIENT");
    }

    private static AppUser userWith(Role role) {
        AppUser user = new AppUser("clerk", "encoded", true, 1L);
        user.addRole(role);
        return user;
    }

    private static Role role(String name, Permission... permissions) {
        Role role = new Role(name, name);
        for (Permission permission : permissions) {
            role.updatePermission(permission, true);
        }
        return role;
    }

    private static Permission permission(String code) {
        int split = code.indexOf('_');
        String action = split < 0 ? null : code.substring(0, split);
        String entity = split < 0 ? null : code.substring(split + 1);
        return new Permission("test", code, entity, action, false);
    }
}
