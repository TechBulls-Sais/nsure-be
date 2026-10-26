package com.nsure.security.web;

import com.nsure.mipas.capability.Capability;
import com.nsure.mipas.capability.CapabilityService;
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
    private final CapabilityService capabilities;

    public MeController(PlatformSecurityContext security, CapabilityService capabilities) {
        this.security = security;
        this.capabilities = capabilities;
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
        boolean staff = !user.isCustomerPortal();
        return new MeResponse(
                user.getId(),
                user.getUsername(),
                user.isSuperAdmin(),
                user.isCustomerPortal(),
                user.getCustomerRef(),
                roles,
                staff && capabilities.has(Capability.CUSTOMER_WRITE),
                staff && capabilities.has(Capability.POLICY_WRITE),
                staff && capabilities.has(Capability.CLAIM_CREATE),
                staff && capabilities.has(Capability.CLAIM_ASSESS),
                staff && capabilities.has(Capability.CLAIM_APPROVE),
                staff && capabilities.has(Capability.CLAIM_REJECT));
    }

    public record MeResponse(
            Long id,
            String username,
            boolean superAdmin,
            boolean customerPortal,
            String customerRef,
            List<String> roles,
            // Workflow actions this user may take (capabilities, as on the demo server).
            boolean canReviewCustomers,
            boolean canManagePolicies,
            boolean canLodgeClaims,
            boolean canAssessClaims,
            boolean canApproveClaims,
            boolean canRejectClaims
    ) {
    }
}
