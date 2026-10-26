package com.nsure.security.web;

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
        return new MeResponse(
                user.getId(),
                user.getUsername(),
                user.isSuperAdmin(),
                user.isCustomerPortal(),
                user.getCustomerRef(),
                roles);
    }

    public record MeResponse(
            Long id,
            String username,
            boolean superAdmin,
            boolean customerPortal,
            String customerRef,
            List<String> roles
    ) {
    }
}
