package com.nsure.mipas.capability;

import java.util.Arrays;
import java.util.List;

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

    private final String code;

    Capability(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(Capability::code).toList();
    }

    public static Capability fromCode(String code) {
        return Arrays.stream(values())
                .filter(item -> item.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown capability '" + code + "'. Add it to Capability first."));
    }
}
