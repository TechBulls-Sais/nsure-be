package com.nsure.security.config;

import static com.nsure.security.config.ApiAccess.anyOf;
import static com.nsure.security.config.ApiAccess.authenticated;
import static com.nsure.security.config.ApiAccess.customer;
import static com.nsure.security.config.ApiAccess.publicAccess;
import static com.nsure.security.config.ApiAccess.staff;
import static com.nsure.security.config.ApiAccess.superAdmin;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * The API access matrix: every endpoint, and the permission that opens it. This is the one place to read or
 * change who may call what.
 *
 * <ul>
 *   <li>Super Admin and Super user pass every rule ({@link ApiAccess}).</li>
 *   <li>Every other user needs a permission code their roles hold. Default holders today: Claims Assessor holds
 *       the claim codes, Onboarding Specialist the customer review codes, Product Configurator and Actuary the
 *       product and rate table codes. Grant a code to any role in Access admin and that role gets the endpoint.</li>
 *   <li>A request no rule matches is denied. A new endpoint needs a rule here, or
 *       {@code EndpointCoverageTest} fails the build.</li>
 * </ul>
 *
 * <p>The first matching rule wins. Services still check the same codes before they write (defence in depth),
 * so a rule here must never be looser than the service it fronts.
 */
public final class ApiAccessRules {

    public record ApiRule(HttpMethod method, String pattern, ApiAccess access) {
    }

    private ApiAccessRules() {
    }

    public static final List<ApiRule> RULES = List.of(
            // ── public ─────────────────────────────────────────────────────────────────────────────────
            rule(GET, "/actuator/health", publicAccess()),
            rule(GET, "/reference/**", publicAccess()),
            rule(GET, "/public/**", publicAccess()),

            // ── every signed-in user ───────────────────────────────────────────────────────────────────
            rule(GET, "/me", authenticated()),

            // ── customer portal: the service limits a customer to their own policies and claims ────────
            rule(GET, "/portfolio/shop", customer()),
            rule(POST, "/portfolio/shop", customer()),
            rule(POST, "/portfolio/shop/quote", customer()),
            rule(POST, "/portfolio/shop/claims", customer()),
            rule(POST, "/portfolio/shop/policies/*/pay", customer()),
            rule(POST, "/portfolio/shop/policies/*/beneficiaries", customer()),
            rule(DELETE, "/portfolio/shop/policies/*/beneficiaries/*", customer()),

            // ── customers and onboarding (Onboarding Specialist) ───────────────────────────────────────
            rule(GET, "/portfolio/customers", anyOf("READ_CUSTOMER")),
            rule(POST, "/portfolio/customers", anyOf("CREATE_CUSTOMER")),
            rule(POST, "/portfolio/customers/*/kyc", anyOf("KYC_CUSTOMER")),
            rule(POST, "/portfolio/customers/*/edd", anyOf("EDD_CUSTOMER")),
            rule(GET, "/portfolio/history/kyc/*", anyOf("READ_CUSTOMER")),
            rule(GET, "/portfolio/history/edd/*", anyOf("READ_CUSTOMER")),

            // ── applications ───────────────────────────────────────────────────────────────────────────
            rule(GET, "/portfolio/applications", anyOf("READ_APPLICATION")),
            rule(POST, "/portfolio/applications", anyOf("CREATE_APPLICATION")),
            rule(PUT, "/portfolio/applications/*", anyOf("UPDATE_APPLICATION")),

            // ── policies, premiums and schemes ─────────────────────────────────────────────────────────
            rule(GET, "/portfolio/policies", anyOf("READ_POLICY")),
            rule(POST, "/portfolio/policies", anyOf("CREATE_POLICY")),
            rule(POST, "/portfolio/quote", anyOf("CREATE_POLICY", "CREATE_APPLICATION")),
            rule(POST, "/portfolio/policies/sweep", anyOf("UPDATE_POLICY")),
            rule(POST, "/portfolio/policies/*/payments", anyOf("UPDATE_POLICY")),
            rule(POST, "/portfolio/policies/*/cancel", anyOf("UPDATE_POLICY")),
            rule(POST, "/portfolio/policies/*/beneficiaries", anyOf("UPDATE_POLICY")),
            rule(DELETE, "/portfolio/policies/*/beneficiaries/*", anyOf("UPDATE_POLICY")),
            rule(GET, "/portfolio/history/policy/*", anyOf("READ_POLICY")),
            rule(GET, "/portfolio/receipts", anyOf("READ_POLICY")),
            rule(PUT, "/portfolio/receipts/*", anyOf("UPDATE_POLICY")),
            rule(GET, "/portfolio/schemes", anyOf("READ_POLICY")),

            // ── claims (Claims Assessor) ───────────────────────────────────────────────────────────────
            rule(GET, "/portfolio/claims", anyOf("READ_CLAIM")),
            rule(POST, "/portfolio/claims", anyOf("CREATE_CLAIM")),
            rule(GET, "/portfolio/history/claim/*", anyOf("READ_CLAIM")),
            rule(PUT, "/portfolio/claims/*/payee", anyOf("UPDATE_CLAIM", "ASSESS_CLAIM")),
            rule(POST, "/portfolio/claims/*/checks", anyOf("UPDATE_CLAIM")),
            rule(POST, "/portfolio/claims/*/assess", anyOf("ASSESS_CLAIM")),
            rule(POST, "/portfolio/claims/*/approve", anyOf("APPROVE_CLAIM")),
            rule(POST, "/portfolio/claims/*/reject", anyOf("REJECT_CLAIM")),
            rule(POST, "/portfolio/claims/*/pay", anyOf("SETTLE_CLAIM")),

            // ── product catalogue (Product Configurator, Actuary) ──────────────────────────────────────
            // The catalogue is already public on the landing page; staff forms need it to pick a product.
            rule(GET, "/portfolio/products", staff()),
            rule(GET, "/portfolio/product-configs", anyOf("READ_PRODUCT")),
            rule(POST, "/portfolio/product-configs", anyOf("CREATE_PRODUCT")),
            rule(PUT, "/portfolio/product-configs/*", anyOf("UPDATE_PRODUCT")),

            // ── rate tables (Actuary, Product Configurator): function code or capability ───────────────
            rule(GET, "/admin/rate-tables", anyOf("READ_RATE_TABLE", "rate.read")),
            rule(GET, "/admin/rate-tables/*", anyOf("READ_RATE_TABLE", "rate.read")),
            rule(PUT, "/admin/rate-tables/*/grid", anyOf("UPDATE_RATE_TABLE", "rate.write")),

            // ── operational controls ───────────────────────────────────────────────────────────────────
            rule(GET, "/admin/controls", anyOf("READ_CONTROL", "controls.read")),

            // ── audit log and maker-checker inbox ──────────────────────────────────────────────────────
            rule(GET, "/audits", anyOf("READ_AUDIT")),
            rule(GET, "/audits/*", anyOf("READ_AUDIT")),
            // The service then needs CHECKER_<code of the pending command> or CHECKER_SUPER_USER.
            rule(POST, "/audits/*/approve", anyOf(
                    "CHECKER_SUPER_USER", "CHECKER_UPDATE_USER", "CHECKER_UPDATE_ROLE", "CHECKER_PERMISSIONS_ROLE")),
            rule(POST, "/audits/*/reject", anyOf(
                    "CHECKER_SUPER_USER", "CHECKER_UPDATE_USER", "CHECKER_UPDATE_ROLE", "CHECKER_PERMISSIONS_ROLE")),

            // ── organisation ───────────────────────────────────────────────────────────────────────────
            rule(GET, "/offices", anyOf("READ_OFFICE")),

            // ── access administration: Super Admin / Super user only ───────────────────────────────────
            rule(GET, "/users", superAdmin()),
            rule(GET, "/users/*", superAdmin()),
            rule(POST, "/users", superAdmin()),
            rule(PUT, "/users/*", superAdmin()),
            rule(DELETE, "/users/*", superAdmin()),
            rule(GET, "/roles", superAdmin()),
            rule(GET, "/roles/*", superAdmin()),
            rule(POST, "/roles", superAdmin()),
            rule(PUT, "/roles/*", superAdmin()),
            rule(PUT, "/roles/*/permissions", superAdmin()),
            rule(DELETE, "/roles/*", superAdmin()),
            rule(GET, "/permissions", superAdmin()),
            rule(GET, "/permissions/*", superAdmin()),
            rule(POST, "/permissions", superAdmin()),
            rule(PUT, "/permissions/*", superAdmin()),
            rule(DELETE, "/permissions/*", superAdmin()));

    private static ApiRule rule(HttpMethod method, String pattern, ApiAccess access) {
        return new ApiRule(method, pattern, access);
    }
}
