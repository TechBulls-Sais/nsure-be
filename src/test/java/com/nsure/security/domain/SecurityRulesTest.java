package com.nsure.security.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.OfficeAccessValidator;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecurityRulesTest {

    @Test
    void allFunctionsCoversWritesAndReads() {
        AppUser user = userWith(permission("ALL_FUNCTIONS"));

        user.validateHasUpdatePermission("USER");
        user.validateHasReadPermission("AUDIT");
        assertThat(user.hasPermissionTo("CHECKER_SUPER_USER")).isTrue();
    }

    @Test
    void allFunctionsReadCoversReadsOnly() {
        AppUser user = userWith(permission("ALL_FUNCTIONS_READ"));

        user.validateHasReadPermission("CLIENT");
        assertThatThrownBy(() -> user.validateHasUpdatePermission("CLIENT"))
                .isInstanceOf(NoAuthorizationException.class);
        assertThat(user.hasPermissionTo("UPDATE_CLIENT")).isFalse();
    }

    @Test
    void disabledRoleGrantsNothing() {
        Role role = new Role();
        role.setDisabled(true);
        role.updatePermission(permission("READ_ROLE"), true);
        AppUser user = new AppUser();
        user.replaceRoles(Set.of(role));

        assertThat(user.getAuthorities()).isEmpty();
        assertThatThrownBy(() -> user.validateHasReadPermission("ROLE"))
                .isInstanceOf(NoAuthorizationException.class);
    }

    @Test
    void enabledRoleBecomesAnAuthority() {
        AppUser user = userWith(permission("READ_ROLE"));

        assertThat(user.getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .contains("READ_ROLE");
        user.validateHasReadPermission("ROLE");
    }

    @Test
    void permissionCodesAreActionThenEntity() {
        assertThat(PermissionCode.code("create", "client")).isEqualTo("CREATE_CLIENT");
    }

    @Test
    void officeScopeUsesHierarchyPrefix() {
        OfficeAccessValidator access = new OfficeAccessValidator();

        access.validateAccess(".2.3.", ".");
        access.validateAccess(".2.3.", ".2.");
        assertThat(access.isHeadOffice(".")).isTrue();
        assertThatThrownBy(() -> access.validateAccess(".", ".2."))
                .isInstanceOf(NoAuthorizationException.class);
        assertThatThrownBy(() -> access.validateAccess(null, "."))
                .isInstanceOf(NoAuthorizationException.class);
    }

    private static AppUser userWith(Permission permission) {
        Role role = new Role();
        role.updatePermission(permission, true);
        AppUser user = new AppUser();
        user.replaceRoles(Set.of(role));
        return user;
    }

    private static Permission permission(String code) {
        Permission permission = new Permission();
        permission.setCode(code);
        return permission;
    }
}
