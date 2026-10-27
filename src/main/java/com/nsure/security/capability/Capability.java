package com.nsure.security.capability;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum Capability {
    DASHBOARD_READ("dashboard.read"),
    CLAIM_READ("claim.read"),
    CLAIM_CREATE("claim.create"),
    CLAIM_ASSESS("claim.assess"),
    CLAIM_APPROVE("claim.approve"),
    CLAIM_REJECT("claim.reject"),
    POLICY_READ("policy.read"),
    POLICY_CREATE("policy.create"),
    POLICY_WRITE("policy.write"),
    PAYMENT_READ("payment.read"),
    CUSTOMER_READ("customer.read"),
    CUSTOMER_WRITE("customer.write"),
    PRODUCT_READ("product.read"),
    PRODUCT_WRITE("product.write"),
    PRODUCT_ACTIVATE("product.activate"),
    PRODUCT_DELETE("product.delete"),
    TIER_WRITE("tier.write"),
    AUDIT_READ("audit.read"),
    FINANCE_EXPORT("finance.export"),
    RATE_READ("rate.read"),
    RATE_WRITE("rate.write"),
    RATE_PUBLISH("rate.publish"),
    CONTROLS_READ("controls.read"),
    CONTROLS_WRITE("controls.write"),
    CLAIM_APPROVE_OVERRIDE("claim.approve.override"),
    RATE_PUBLISH_SELF("rate.publish.self");

    public static final Set<String> PROTECTED_ROLES = Set.of(
            "Policyholder / End User", "Guest", "All");

    private final String code;

    Capability(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(Capability::code).collect(Collectors.toUnmodifiableSet());
    }

    public static Capability require(String code) {
        for (Capability capability : values()) {
            if (capability.code.equals(code)) {
                return capability;
            }
        }
        throw new IllegalArgumentException(
                "Unknown capability '" + code + "'. Add it to Capability first. Known: " + codes());
    }

    public static void assertGrantable(String roleName, String capability) {
        require(capability);
        if (PROTECTED_ROLES.contains(roleName)) {
            throw new IllegalArgumentException(
                    "Role '" + roleName + "' may never be granted a capability.");
        }
    }
}
