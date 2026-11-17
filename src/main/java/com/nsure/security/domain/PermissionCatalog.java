package com.nsure.security.domain;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The closed permission vocabulary (spec 04 §3, rule A-4). Every code the application checks is named here; the
 * {@code app_permission} table holds exactly these codes ({@code PermissionCatalogTest} asserts both directions), and
 * asking for a code that is not here is a programming error, not a silent deny.
 *
 * <p>Codes are {@code ACTION_ENTITY} (docs/IMPLEMENT_RBAC.md). Where a code realises one of the spec's capabilities
 * ({@code claim.approve}, {@code rate.publish} …) the capability is recorded alongside, so the spec grant matrix can be
 * checked against the seeded roles.
 */
public enum PermissionCatalog {

    // ── special ───────────────────────────────────────────────────────────────────────────────────────────
    // Maker-checker approvals (docs/IMPLEMENT_AUDITING.md): one per maker-checker code, never a wildcard.
    CHECKER_PERMISSIONS_ROLE(null),
    CHECKER_UPDATE_ROLE(null),
    CHECKER_UPDATE_USER(null),
    CHECKER_CREATE_USER(null),
    CHECKER_DELETE_USER(null),

    // ── access administration (hand-gated, spec 04 A-18: no role holds these by default) ──────────────────
    CREATE_USER(null),
    READ_USER(null),
    UPDATE_USER(null),
    DELETE_USER(null),
    CREATE_ROLE(null),
    READ_ROLE(null),
    UPDATE_ROLE(null),
    DELETE_ROLE(null),
    PERMISSIONS_ROLE(null),
    CREATE_PERMISSION(null),
    READ_PERMISSION(null),
    UPDATE_PERMISSION(null),
    DELETE_PERMISSION(null),
    // Emergency access (Nsure Life NS-SH11-003): time-limited, justified, alerted and reviewed.
    GRANT_EMERGENCY_ACCESS(null),
    REVIEW_EMERGENCY_ACCESS(null),

    // IL02 underwriting (Nsure Life NS-IL02-001..006).
    READ_UNDERWRITING(null),
    UNDERWRITE_APPLICATION(null),
    MANAGE_UNDERWRITING(null),
    // IL03/IL04 policy servicing (Nsure Life NS-IL03-002..006, NS-IL04-001..006).
    READ_SERVICING(null),
    MANAGE_SERVICING(null),
    APPROVE_ENDORSEMENT(null),
    BACKDATE_ENDORSEMENT(null),
    APPROVE_BENEFIT(null),
    MANAGE_BENEFIT_RULE(null),
    // IL05 individual credit life (Nsure Life NS-IL05-001..006).
    READ_CREDIT(null),
    MANAGE_CREDIT(null),
    PAY_CREDIT_REFUND(null),
    MANAGE_CREDIT_RULE(null),
    // Partner portals (specification section 04): scoped to one intermediary, scheme or lender.
    USE_PARTNER_PORTAL(null),

    // ── spec 04 capabilities ────────────────────────────────────────────────────────────────────────────
    READ_DASHBOARD("dashboard.read"),
    READ_CLAIM("claim.read"),
    CREATE_CLAIM("claim.create"),
    ASSESS_CLAIM("claim.assess"),
    UPDATE_CLAIM("claim.assess"),
    APPROVE_CLAIM("claim.approve"),
    SETTLE_CLAIM("claim.approve"),
    REJECT_CLAIM("claim.reject"),
    OVERRIDE_CLAIM_FOUR_EYES("claim.approve.override"),
    DELETE_CLAIM(null),
    READ_POLICY("policy.read"),
    READ_APPLICATION("policy.read"),
    CREATE_POLICY("policy.create"),
    CREATE_APPLICATION("policy.create"),
    UPDATE_APPLICATION("policy.create"),
    UPDATE_POLICY("policy.write"),
    DELETE_POLICY(null),
    DELETE_APPLICATION(null),
    READ_PAYMENT("payment.read"),
    READ_CUSTOMER("customer.read"),
    CREATE_CUSTOMER("customer.write"),
    UPDATE_CUSTOMER("customer.write"),
    KYC_CUSTOMER("customer.write"),
    EDD_CUSTOMER("customer.write"),
    DELETE_CUSTOMER(null),
    READ_PRODUCT("product.read"),
    CREATE_PRODUCT("product.write"),
    UPDATE_PRODUCT("product.write"),
    ACTIVATE_PRODUCT("product.activate"),
    DELETE_PRODUCT("product.delete"),
    UPDATE_TIER("tier.write"),
    READ_AUDIT("audit.read"),
    EXPORT_GL("finance.export"),
    READ_RATE_TABLE("rate.read"),
    CREATE_RATE_TABLE("rate.write"),
    UPDATE_RATE_TABLE("rate.write"),
    PUBLISH_RATE_TABLE("rate.publish"),
    PUBLISH_OWN_RATE_TABLE("rate.publish.self"),
    READ_CONTROL("controls.read"),
    UPDATE_CONTROL("controls.write"),

    // ── Nsure Life modules (docs/NSURE_LIFE_IMPLEMENTATION_PLAN.md §3.10) ───────────────────────────────
    READ_SCHEME(null),
    MANAGE_SCHEME(null),
    PROCESS_CENSUS(null),
    UNDERWRITE_MEMBER(null),
    BILL_SCHEME(null),
    READ_FINANCE(null),
    MANAGE_FINANCE(null),
    APPROVE_JOURNAL(null),
    CLOSE_PERIOD(null),
    READ_DISTRIBUTION(null),
    MANAGE_DISTRIBUTION(null),
    PAY_COMMISSION(null),
    READ_REINSURANCE(null),
    MANAGE_REINSURANCE(null),
    APPROVE_TREATY(null),
    READ_COMMUNICATION(null),
    MANAGE_COMMUNICATION(null),
    APPROVE_TEMPLATE(null),
    READ_SERVICE_CASE(null),
    MANAGE_SERVICE_CASE(null),
    READ_INTEGRATION(null),
    MANAGE_INTEGRATION(null),
    READ_REPORT(null),

    // ── insurer configuration releases (docs/INSURER_DEPLOYMENT_PLAN.md) ────────────────────────────────
    READ_CONFIG(null),
    MANAGE_CONFIG(null),
    APPROVE_CONFIG(null),
    ACTIVATE_CONFIG(null);

    /** Break-glass codes: granted to no role by default (spec 12 C-9, C-10; 11 §6.1). */
    public static final Set<String> BREAK_GLASS = Set.of(
            UPDATE_CONTROL.name(), OVERRIDE_CLAIM_FOUR_EYES.name(), PUBLISH_OWN_RATE_TABLE.name());

    private final String capability;

    PermissionCatalog(String capability) {
        this.capability = capability;
    }

    public String code() {
        return name();
    }

    /** The spec capability this code realises, or null for a code outside the spec 04 vocabulary. */
    public String capability() {
        return capability;
    }

    public static Set<String> codes() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The spec 04 capabilities, in declaration order. */
    public static Set<String> capabilities() {
        return Arrays.stream(values())
                .map(PermissionCatalog::capability)
                .filter(c -> c != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Codes that together realise one spec capability. */
    public static Set<String> codesFor(String capability) {
        Set<String> codes = Arrays.stream(values())
                .filter(p -> capability.equals(p.capability))
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (codes.isEmpty()) {
            throw new IllegalArgumentException("Unknown capability '" + capability + "'. Known: " + capabilities());
        }
        return codes;
    }

    public static boolean isKnown(String code) {
        if (code == null) {
            return false;
        }
        String upper = code.toUpperCase(Locale.ROOT);
        if (contains(upper)) {
            return true;
        }
        // Maker-checker approval codes: CHECKER_<any known code> (docs/IMPLEMENT_AUDITING.md).
        return upper.startsWith("CHECKER_") && contains(upper.substring("CHECKER_".length()));
    }

    /**
     * Rule A-4: an unknown code is a typo in source and must be loud. A silent deny is how an endpoint ends up
     * unreachable for a week with nobody understanding why.
     */
    public static String require(String code) {
        if (!isKnown(code)) {
            throw new IllegalArgumentException(
                    "Unknown permission code '" + code + "'. Add it to PermissionCatalog and seed it first.");
        }
        return code.toUpperCase(Locale.ROOT);
    }

    private static boolean contains(String code) {
        for (PermissionCatalog value : values()) {
            if (value.name().equals(code)) {
                return true;
            }
        }
        return false;
    }
}
