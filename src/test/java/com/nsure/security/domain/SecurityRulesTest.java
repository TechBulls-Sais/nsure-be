package com.nsure.security.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nsure.security.exception.NoAuthorizationException;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecurityRulesTest {

    @Test
    void thereAreNoWildcardCodes() {
        // Nsure Life NS-SH11-002: deny by default, explicit grants only. The old blanket codes are not codes at all.
        assertThat(PermissionCatalog.isKnown("ALL_FUNCTIONS")).isFalse();
        assertThat(PermissionCatalog.isKnown("ALL_FUNCTIONS_READ")).isFalse();
        assertThat(PermissionCatalog.isKnown("CHECKER_SUPER_USER")).isFalse();
        assertThatThrownBy(() -> PermissionCatalog.require("ALL_FUNCTIONS")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aGrantCoversOnlyItsOwnCode() {
        AppUser user = userWith(permission("READ_CLAIM"));

        user.validateHasReadPermission("CLAIM");
        assertThat(user.hasPermissionTo("READ_POLICY")).isFalse();
        assertThatThrownBy(() -> user.validateHasUpdatePermission("CLAIM"))
                .isInstanceOf(NoAuthorizationException.class);
        assertThat(user.hasPermissionTo("APPROVE_CLAIM")).isFalse();
        // Spec 04 A-4: an unknown code is a programming error, not a silent deny.
        assertThatThrownBy(() -> user.hasPermissionTo("UPDATE_CLIENT")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theAdministratorRoleNameGrantsNothing() {
        Role admin = new Role();
        admin.setName(AppUser.SUPER_ADMIN_ROLE);
        admin.setDeskAccess(true);
        AppUser user = new AppUser();
        user.replaceRoles(Set.of(admin));

        assertThat(user.isSuperAdmin()).isTrue();
        assertThat(user.hasPermissionTo("READ_AUDIT")).isFalse();
        assertThat(user.hasPermissionTo("CHECKER_UPDATE_ROLE")).isFalse();
        assertThat(user.effectivePermissions()).isEmpty();
    }

    @Test
    void emergencyAccessCountsOnlyWhileActive() {
        AppUser user = userWith(permission("READ_CONTROL"));
        assertThat(user.hasPermissionTo("UPDATE_CONTROL")).isFalse();

        EmergencyAccessGrant grant = new EmergencyAccessGrant(user, "UPDATE_CONTROL", "Incident 42 needs a switch",
                "secadmin", LocalDateTime.now().plusHours(2));
        assertThat(grant.status()).isEqualTo("ACTIVE");
        assertThat(user.hasPermissionTo("UPDATE_CONTROL")).isTrue();
        assertThat(user.getAuthorities()).extracting(a -> a.getAuthority()).contains("UPDATE_CONTROL");

        grant.revoke("secadmin");
        assertThat(grant.status()).isEqualTo("REVOKED");
        assertThat(user.hasPermissionTo("UPDATE_CONTROL")).isFalse();

        EmergencyAccessGrant lapsed = new EmergencyAccessGrant(user, "PUBLISH_OWN_RATE_TABLE", "Old incident",
                "secadmin", LocalDateTime.now().minusMinutes(1));
        assertThat(lapsed.status()).isEqualTo("EXPIRED");
        assertThat(user.hasPermissionTo("PUBLISH_OWN_RATE_TABLE")).isFalse();
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
