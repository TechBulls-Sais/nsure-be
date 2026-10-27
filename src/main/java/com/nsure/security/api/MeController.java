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
                        .map(Role::getName)
                        .sorted(Comparator.naturalOrder())
                        .toList();
        boolean customerPortal = user.isPortalCustomer();
        return new MeResponse(
                user.getId(),
                user.getUsername(),
                user.isSuperAdmin(),
                roles,
                customerPortal,
                user.hasPermissionTo("KYC_CUSTOMER"),
                user.hasPermissionTo("EDD_CUSTOMER"),
                user.hasPermissionTo("UPDATE_POLICY"),
                user.hasPermissionTo("CREATE_CLAIM"),
                user.hasPermissionTo("UPDATE_CLAIM"),
                user.hasPermissionTo("ASSESS_CLAIM"),
                user.hasPermissionTo("APPROVE_CLAIM"),
                user.hasPermissionTo("REJECT_CLAIM"),
                user.hasPermissionTo("SETTLE_CLAIM"));
    }
}
