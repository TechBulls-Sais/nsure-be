package com.nsure.security.api;

import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Role;
import com.nsure.security.service.PlatformSecurityContext;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    private final PlatformSecurityContext security;

    public MeController(PlatformSecurityContext security) {
        this.security = security;
    }

    @GetMapping("/me")
    public MeResponse me() {
        AppUser user = security.authenticatedUser();
        List<String> roles = user.getRoles() == null
                ? List.of()
                : user.getRoles().stream()
                        .filter(role -> !role.isDisabled())
                        .map(Role::getName)
                        .sorted(Comparator.naturalOrder())
                        .toList();
        boolean customerPortal = user.isPortalCustomer();
        // A portal customer holds no permission (A-2); report none even if a stray grant exists.
        List<String> permissions = customerPortal ? List.of() : List.copyOf(user.effectivePermissions());
        return new MeResponse(
                user.getId(),
                user.getUsername(),
                user.isSuperAdmin(),
                roles,
                customerPortal,
                user.isStaffUser(),
                permissions.contains("KYC_CUSTOMER"),
                permissions.contains("EDD_CUSTOMER"),
                permissions.contains("UPDATE_POLICY"),
                permissions.contains("CREATE_CLAIM"),
                permissions.contains("UPDATE_CLAIM"),
                permissions.contains("ASSESS_CLAIM"),
                permissions.contains("APPROVE_CLAIM"),
                permissions.contains("REJECT_CLAIM"),
                permissions.contains("SETTLE_CLAIM"),
                permissions,
                customerPortal ? List.of() : user.activeEmergencyGrants().stream()
                        .map(grant -> new MeResponse.EmergencyAccess(
                                grant.getPermissionCode(), grant.getExpiresAt(), grant.getGrantedBy()))
                        .toList());
    }
}
