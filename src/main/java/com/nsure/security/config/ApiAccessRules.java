package com.nsure.security.config;

import static com.nsure.security.config.ApiAccess.anyOf;
import static com.nsure.security.config.ApiAccess.authenticated;
import static com.nsure.security.config.ApiAccess.customer;
import static com.nsure.security.config.ApiAccess.publicAccess;
import static com.nsure.security.config.ApiAccess.staff;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * The API access matrix: every endpoint, and the permission that opens it. This is the one place to read or
 * change who may call what.
 *
 * <ul>
 *   <li>Deny by default: every user, the administrator included, needs a permission code their roles hold (or an
 *       active emergency grant). No role passes every rule by name. Default holders today: Claims Assessor holds
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
            // Customer self sign-up: creates a Pending KYC customer once the one-time code matches.
            rule(POST, "/public/signup/otp", publicAccess()),
            rule(POST, "/public/signup", publicAccess()),
            // Payment settlement triggers (spec 13 §6): each only names a transaction to verify server-side, never a
            // verdict. The simulator stands in for the provider's hosted page until DPO credentials exist.
            rule(GET, "/payment/return", publicAccess()),
            rule(POST, "/payment/callback", publicAccess()),
            // Email/WhatsApp provider callbacks (Blueprint §5): authenticated by their HMAC signature, not a login.
            rule(POST, "/integration/messaging/status", publicAccess()),
            rule(POST, "/integration/messaging/inbound", publicAccess()),
            rule(GET, "/payment/simulator/*", publicAccess()),
            rule(POST, "/payment/simulator/*", publicAccess()),

            // ── every signed-in user ───────────────────────────────────────────────────────────────────
            rule(POST, "/auth/session", authenticated()),
            // Second sign-in step for staff and partners: the challenge from POST /auth/session and its one-time code.
            rule(POST, "/auth/session/verify", publicAccess()),
            rule(DELETE, "/auth/session", authenticated()),
            rule(GET, "/me", authenticated()),
            // Staff alerts; each user sees only alerts whose permission they hold.
            rule(GET, "/me/alerts", authenticated()),

            // ── customer portal: the service limits a customer to their own policies and claims ────────
            rule(GET, "/portfolio/shop", customer()),
            rule(POST, "/portfolio/shop", customer()),
            rule(POST, "/portfolio/shop/quote", customer()),
            rule(POST, "/portfolio/shop/claims", customer()),
            rule(POST, "/portfolio/shop/claims/*/documents", customer()),
            rule(GET, "/portfolio/shop/claims/*/documents/*", customer()),
            // Declaration and consent before the first premium (spec 01 BR-5, BR-6).
            rule(GET, "/portfolio/shop/declaration", customer()),
            rule(POST, "/portfolio/shop/policies/*/declaration/otp", customer()),
            rule(POST, "/portfolio/shop/policies/*/declaration", customer()),
            rule(POST, "/portfolio/shop/policies/*/pay", customer()),
            // Checkout (spec 13 §8.1): a customer pays their own policy; polling checks ownership or READ_PAYMENT.
            rule(POST, "/checkout/*/pay", customer()),
            rule(GET, "/checkout/transactions/*", authenticated()),
            rule(POST, "/portfolio/shop/policies/*/beneficiaries", customer()),
            rule(DELETE, "/portfolio/shop/policies/*/beneficiaries/*", customer()),
            rule(POST, "/portfolio/shop/policies/*/documents/schedule", customer()),
            rule(GET, "/portfolio/shop/policies/*/documents/*", customer()),

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
            rule(GET, "/portfolio/applications/*/checklist", anyOf("READ_APPLICATION")),
            rule(GET, "/portfolio/application-questions", anyOf("READ_APPLICATION", "CREATE_APPLICATION")),
            rule(PUT, "/portfolio/applications/*/details", anyOf("UPDATE_APPLICATION")),
            rule(POST, "/portfolio/applications/*/submit", anyOf("CREATE_APPLICATION", "UPDATE_APPLICATION")),
            rule(POST, "/portfolio/applications/*/evidence", anyOf("UPDATE_APPLICATION")),

            // ── policies, premiums and schemes ─────────────────────────────────────────────────────────
            rule(GET, "/portfolio/policies", anyOf("READ_POLICY")),
            rule(POST, "/portfolio/policies", anyOf("CREATE_POLICY")),
            rule(POST, "/portfolio/quote", anyOf("CREATE_POLICY", "CREATE_APPLICATION")),
            rule(POST, "/portfolio/quotes", anyOf("CREATE_POLICY", "CREATE_APPLICATION")),
            rule(GET, "/portfolio/quotes", anyOf("READ_APPLICATION", "READ_POLICY")),
            rule(GET, "/portfolio/quotes/*", anyOf("READ_APPLICATION", "READ_POLICY")),
            rule(GET, "/portfolio/quotes/*/replay", anyOf("READ_APPLICATION", "READ_POLICY")),
            rule(POST, "/portfolio/quotes/*/accept", anyOf("CREATE_POLICY", "CREATE_APPLICATION")),
            rule(POST, "/portfolio/quotes/*/requote", anyOf("CREATE_POLICY", "CREATE_APPLICATION")),
            rule(POST, "/portfolio/policies/sweep", anyOf("UPDATE_POLICY")),
            rule(POST, "/portfolio/policies/*/payments", anyOf("UPDATE_POLICY")),
            rule(POST, "/portfolio/policies/*/cancel", anyOf("UPDATE_POLICY")),
            rule(POST, "/portfolio/policies/*/beneficiaries", anyOf("UPDATE_POLICY")),
            rule(DELETE, "/portfolio/policies/*/beneficiaries/*", anyOf("UPDATE_POLICY")),
            rule(GET, "/portfolio/history/policy/*", anyOf("READ_POLICY")),
            // The declaration consent log is evidence: audit.read (spec 04 §7).
            rule(GET, "/portfolio/policies/*/declarations", anyOf("READ_AUDIT")),
            rule(GET, "/portfolio/policies/*/documents", anyOf("READ_POLICY")),
            rule(GET, "/portfolio/policies/*/documents/*", anyOf("READ_POLICY")),
            rule(POST, "/portfolio/policies/*/documents/schedule", anyOf("CREATE_POLICY", "UPDATE_POLICY")),
            // Blueprint §8: a controlled replacement with a reason (the old schedule is kept), and the download log.
            rule(POST, "/portfolio/policies/*/documents/*/reissue", anyOf("CREATE_POLICY", "UPDATE_POLICY")),
            rule(GET, "/portfolio/policies/*/document-access", anyOf("READ_AUDIT")),
            rule(GET, "/portfolio/receipts", anyOf("READ_PAYMENT")),
            rule(GET, "/payments/transactions", anyOf("READ_PAYMENT")),
            rule(GET, "/payments/transactions/*/log", anyOf("READ_PAYMENT")),
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
            rule(POST, "/portfolio/claims/*/confirm-payout", anyOf("SETTLE_CLAIM")),
            rule(POST, "/portfolio/claims/*/payout-failed", anyOf("SETTLE_CLAIM")),
            rule(POST, "/portfolio/claims/*/reopen", anyOf("APPROVE_CLAIM")),
            rule(GET, "/portfolio/claims/*/financials", anyOf("READ_CLAIM")),
            rule(GET, "/portfolio/claims/*/documents", anyOf("READ_CLAIM")),
            rule(GET, "/portfolio/claims/*/documents/*", anyOf("READ_CLAIM")),
            rule(POST, "/portfolio/claims/*/documents", anyOf("UPDATE_CLAIM", "CREATE_CLAIM", "ASSESS_CLAIM")),
            rule(POST, "/portfolio/claims/*/documents/summary", anyOf("UPDATE_CLAIM", "CREATE_CLAIM", "ASSESS_CLAIM")),
            rule(GET, "/portfolio/claims/*/document-access", anyOf("READ_AUDIT")),

            // ── product catalogue (Product Configurator, Actuary) ──────────────────────────────────────
            // The catalogue is already public on the landing page; staff forms need it to pick a product.
            rule(GET, "/portfolio/products", staff()),
            rule(GET, "/portfolio/products/*/versions", anyOf("READ_PRODUCT")),
            rule(POST, "/portfolio/products/*/versions", anyOf("UPDATE_PRODUCT")),
            rule(POST, "/portfolio/products/*/versions/*/approve", anyOf("ACTIVATE_PRODUCT")),
            rule(POST, "/portfolio/products/*/versions/*/reject", anyOf("ACTIVATE_PRODUCT")),
            rule(GET, "/portfolio/policies/*/terms", anyOf("READ_POLICY")),
            rule(GET, "/portfolio/product-configs", anyOf("READ_PRODUCT")),
            rule(POST, "/portfolio/product-configs", anyOf("CREATE_PRODUCT")),
            rule(PUT, "/portfolio/product-configs/*", anyOf("UPDATE_PRODUCT")),
            rule(GET, "/portfolio/product-configs/*/versions", anyOf("READ_PRODUCT")),

            // ── rate tables (Actuary, Product Configurator): function code or capability ───────────────
            rule(GET, "/admin/rate-tables", anyOf("READ_RATE_TABLE")),
            rule(GET, "/admin/rate-tables/*", anyOf("READ_RATE_TABLE")),
            rule(PUT, "/admin/rate-tables/*/grid", anyOf("UPDATE_RATE_TABLE")),
            // Spec 11 §8: the version workflow. rate.write builds and submits; rate.publish approves (Actuary only).
            rule(GET, "/admin/rate-tables/*/versions/*/grid", anyOf("READ_RATE_TABLE")),
            rule(GET, "/admin/rate-tables/*/versions/*/impact", anyOf("READ_RATE_TABLE")),
            rule(GET, "/admin/rate-tables/*/versions/*/diff/*", anyOf("READ_RATE_TABLE")),
            rule(GET, "/admin/rate-tables/*/export", anyOf("READ_RATE_TABLE")),
            rule(PUT, "/admin/rate-tables/*/versions/*/grid", anyOf("UPDATE_RATE_TABLE")),
            rule(PATCH, "/admin/rate-tables/*/versions/*/cells", anyOf("UPDATE_RATE_TABLE")),
            rule(POST, "/admin/rate-tables/*/versions/*/submit", anyOf("UPDATE_RATE_TABLE")),
            rule(POST, "/admin/rate-tables/*/import", anyOf("UPDATE_RATE_TABLE")),
            rule(POST, "/admin/rate-tables/*/import/confirm", anyOf("UPDATE_RATE_TABLE")),
            rule(POST, "/admin/rate-tables/*/scale", anyOf("UPDATE_RATE_TABLE")),
            rule(POST, "/admin/rate-tables/*/versions/*/approve", anyOf("PUBLISH_RATE_TABLE")),
            rule(POST, "/admin/rate-tables/*/versions/*/reject", anyOf("PUBLISH_RATE_TABLE")),
            rule(POST, "/admin/rate-tables/*/versions/*/cancel", anyOf("PUBLISH_RATE_TABLE")),

            // ── operational controls ───────────────────────────────────────────────────────────────────
            rule(GET, "/admin/controls", anyOf("READ_CONTROL")),
            rule(GET, "/admin/controls/stp-dashboard", anyOf("READ_CONTROL")),
            rule(GET, "/admin/controls/stp-policies", anyOf("READ_CONTROL")),
            rule(GET, "/admin/controls/*/history", anyOf("READ_CONTROL")),
            // controls.write: granted to no role by default (spec 12 C-10).
            rule(PUT, "/admin/controls/stp-policies/*", anyOf("UPDATE_CONTROL")),
            rule(PUT, "/admin/controls/*", anyOf("UPDATE_CONTROL")),

            // ── audit log and maker-checker inbox ──────────────────────────────────────────────────────
            rule(GET, "/audits", anyOf("READ_AUDIT")),
            rule(GET, "/audits/*", anyOf("READ_AUDIT")),
            rule(GET, "/audits/evidence/verify", anyOf("READ_AUDIT")),
            rule(GET, "/audits/evidence/export", anyOf("READ_AUDIT")),
            // The service then needs CHECKER_<code of the pending command>, and the checker is never the maker.
            rule(POST, "/audits/*/approve", anyOf("CHECKER_CREATE_USER", "CHECKER_UPDATE_USER", "CHECKER_DELETE_USER",
                    "CHECKER_UPDATE_ROLE", "CHECKER_PERMISSIONS_ROLE")),
            rule(POST, "/audits/*/reject", anyOf("CHECKER_CREATE_USER", "CHECKER_UPDATE_USER", "CHECKER_DELETE_USER",
                    "CHECKER_UPDATE_ROLE", "CHECKER_PERMISSIONS_ROLE")),

            // ── emergency access (Nsure Life NS-SH11-003) ──────────────────────────────────────────────
            // Time-limited break-glass grants: an identity administrator grants or revokes, Compliance reviews.
            rule(GET, "/admin/emergency-access", anyOf(
                    "GRANT_EMERGENCY_ACCESS", "REVIEW_EMERGENCY_ACCESS", "READ_AUDIT")),
            rule(POST, "/admin/emergency-access", anyOf("GRANT_EMERGENCY_ACCESS")),
            rule(POST, "/admin/emergency-access/*/revoke", anyOf("GRANT_EMERGENCY_ACCESS")),
            rule(POST, "/admin/emergency-access/*/review", anyOf("REVIEW_EMERGENCY_ACCESS")),
            // Security events with an owner and escalation route: auditors list; the service checks the event's code.
            rule(GET, "/admin/security-events", anyOf("READ_AUDIT")),
            rule(POST, "/admin/security-events/*/own", anyOf("READ_AUDIT", "READ_CONTROL", "READ_PAYMENT",
                    "UPDATE_USER")),
            rule(POST, "/admin/security-events/*/resolve", anyOf("READ_AUDIT", "READ_CONTROL", "READ_PAYMENT",
                    "UPDATE_USER")),
            // Access recertification (NS-SH11-007): reviewers decide, auditors read.
            rule(GET, "/admin/access-reviews", anyOf("REVIEW_ACCESS", "READ_AUDIT")),
            rule(GET, "/admin/access-reviews/*", anyOf("REVIEW_ACCESS", "READ_AUDIT")),
            rule(POST, "/admin/access-reviews", anyOf("REVIEW_ACCESS")),
            rule(POST, "/admin/access-reviews/*/items/*/decision", anyOf("REVIEW_ACCESS")),
            rule(POST, "/admin/access-reviews/*/close", anyOf("REVIEW_ACCESS")),

            // ── group administration GL01-GL05 (Group Administrator) ───────────────────────────────────
            rule(GET, "/group/schemes", anyOf("READ_SCHEME")),
            rule(GET, "/group/schemes/*", anyOf("READ_SCHEME")),
            rule(POST, "/group/schemes", anyOf("MANAGE_SCHEME")),
            rule(POST, "/group/schemes/*/categories", anyOf("MANAGE_SCHEME")),
            rule(PUT, "/group/schemes/*/categories/*", anyOf("MANAGE_SCHEME")),
            rule(POST, "/group/schemes/*/census-batches", anyOf("PROCESS_CENSUS")),
            rule(GET, "/group/census-batches/*", anyOf("READ_SCHEME")),
            rule(POST, "/group/census-batches/*/commit", anyOf("PROCESS_CENSUS")),
            rule(POST, "/group/members/*/underwriting", anyOf("UNDERWRITE_MEMBER")),
            rule(POST, "/group/schemes/*/invoices", anyOf("BILL_SCHEME")),
            rule(POST, "/group/invoices/*/receipts", anyOf("BILL_SCHEME")),

            // ── insurance subledger SH08 (Finance Controller) ──────────────────────────────────────────
            rule(GET, "/finance/**", anyOf("READ_FINANCE")),
            rule(PUT, "/finance/posting-rules/*", anyOf("MANAGE_FINANCE")),
            rule(POST, "/finance/posting-rules/*/versions/*/approve", anyOf("APPROVE_JOURNAL")),
            rule(POST, "/finance/posting-rules/*/versions/*/reject", anyOf("APPROVE_JOURNAL")),
            rule(POST, "/finance/journals/*/reverse", anyOf("MANAGE_FINANCE")),
            rule(POST, "/finance/exceptions/*/retry", anyOf("MANAGE_FINANCE")),
            rule(POST, "/finance/periods/*/*", anyOf("CLOSE_PERIOD")),
            rule(POST, "/finance/gl-batches", anyOf("EXPORT_GL")),
            rule(POST, "/finance/gl-batches/*/acknowledge", anyOf("EXPORT_GL")),
            // Blueprint §7: prepared → submitted → accepted/rejected → reconciled; manual journals and suspense clearance
            // are proposed with MANAGE_FINANCE and approved by someone else with APPROVE_JOURNAL.
            rule(POST, "/finance/gl-batches/*/submit", anyOf("EXPORT_GL")),
            rule(POST, "/finance/gl-batches/*/reconcile", anyOf("EXPORT_GL")),
            rule(GET, "/finance/manual-journals", anyOf("READ_FINANCE")),
            rule(POST, "/finance/manual-journals", anyOf("MANAGE_FINANCE")),
            rule(POST, "/finance/manual-journals/*/approve", anyOf("APPROVE_JOURNAL")),
            rule(POST, "/finance/manual-journals/*/reject", anyOf("APPROVE_JOURNAL")),
            rule(GET, "/finance/suspense", anyOf("READ_FINANCE")),
            rule(POST, "/finance/suspense/*/clearance", anyOf("MANAGE_FINANCE")),
            rule(POST, "/finance/suspense/*/clearance/approve", anyOf("APPROVE_JOURNAL")),
            rule(POST, "/finance/suspense/*/clearance/reject", anyOf("APPROVE_JOURNAL")),

            // ── distribution and commissions SH06 (Distribution Administrator) ─────────────────────────
            rule(GET, "/distribution/**", anyOf("READ_DISTRIBUTION")),
            rule(POST, "/distribution/intermediaries", anyOf("MANAGE_DISTRIBUTION")),
            rule(PUT, "/distribution/intermediaries/*", anyOf("MANAGE_DISTRIBUTION")),
            rule(POST, "/distribution/commission-rules", anyOf("MANAGE_DISTRIBUTION")),
            rule(POST, "/distribution/assignments", anyOf("MANAGE_DISTRIBUTION")),
            rule(POST, "/distribution/statements", anyOf("PAY_COMMISSION")),
            rule(POST, "/distribution/statements/*/pay", anyOf("PAY_COMMISSION")),

            // ── individual underwriting IL02 (Underwriter) ─────────────────────────────────────────────
            rule(GET, "/underwriting/**", anyOf("READ_UNDERWRITING")),
            rule(POST, "/underwriting/cases/*/offer", anyOf("UNDERWRITE_APPLICATION", "UPDATE_APPLICATION")),
            rule(POST, "/underwriting/cases/**", anyOf("UNDERWRITE_APPLICATION")),
            rule(PUT, "/underwriting/rules/*", anyOf("MANAGE_UNDERWRITING")),
            rule(PUT, "/underwriting/authorities/*", anyOf("MANAGE_UNDERWRITING")),

            // ── policy servicing IL03/IL04 ─────────────────────────────────────────────────────────────
            rule(GET, "/servicing/**", anyOf("READ_SERVICING")),
            rule(POST, "/servicing/policies/*/endorsements/preview", anyOf("MANAGE_SERVICING")),
            rule(POST, "/servicing/policies/*/endorsements", anyOf("MANAGE_SERVICING")),
            rule(POST, "/servicing/endorsements/*/decision", anyOf("APPROVE_ENDORSEMENT", "UNDERWRITE_APPLICATION")),
            rule(POST, "/servicing/policies/*/reinstatement", anyOf("APPROVE_ENDORSEMENT")),
            rule(POST, "/servicing/policies/*/termination", anyOf("APPROVE_ENDORSEMENT")),
            rule(POST, "/servicing/policies/*/paid-up", anyOf("APPROVE_ENDORSEMENT")),
            rule(POST, "/servicing/policies/*/surrender", anyOf("MANAGE_SERVICING")),
            rule(POST, "/servicing/benefit-events/run", anyOf("MANAGE_SERVICING")),
            rule(POST, "/servicing/benefit-events/*/decision", anyOf("APPROVE_BENEFIT")),
            rule(POST, "/servicing/benefit-events/*/payment", anyOf("APPROVE_BENEFIT")),
            rule(POST, "/servicing/life-reviews/run", anyOf("MANAGE_SERVICING")),
            rule(POST, "/servicing/life-reviews/*/close", anyOf("MANAGE_SERVICING")),
            rule(PUT, "/servicing/benefit-rules", anyOf("MANAGE_BENEFIT_RULE")),
            rule(PUT, "/servicing/rules/*", anyOf("MANAGE_BENEFIT_RULE")),

            // ── individual credit life IL05 (Credit Life Administrator) ────────────────────────────────
            rule(GET, "/credit/**", anyOf("READ_CREDIT")),
            rule(PUT, "/credit/rule", anyOf("MANAGE_CREDIT_RULE")),
            rule(POST, "/credit/refunds/*/payment", anyOf("PAY_CREDIT_REFUND")),
            rule(POST, "/credit/**", anyOf("MANAGE_CREDIT")),

            // ── Care Cash benefit engine (specification section 07) ────────────────────────────────────
            rule(GET, "/care-cash/**", anyOf("READ_CLAIM")),
            rule(POST, "/care-cash/policies/*/assessment", anyOf("CREATE_CLAIM")),
            rule(POST, "/care-cash/policies/*/events", anyOf("CREATE_CLAIM")),
            rule(POST, "/care-cash/events/*/decision", anyOf("APPROVE_CLAIM")),
            rule(POST, "/care-cash/events/*/payment", anyOf("SETTLE_CLAIM")),

            // ── group lifecycle GL01/GL02/GL05/GL06 ────────────────────────────────────────────────────
            rule(GET, "/group-life/**", anyOf("READ_SCHEME")),
            rule(POST, "/group-life/quotes/*/progress", anyOf("MANAGE_SCHEME", "UNDERWRITE_MEMBER")),
            rule(POST, "/group-life/quotes/**", anyOf("MANAGE_SCHEME")),
            rule(POST, "/group-life/quotes", anyOf("MANAGE_SCHEME")),
            rule(PUT, "/group-life/schemes/*/benefits", anyOf("MANAGE_SCHEME")),
            rule(POST, "/group-life/schemes/*/events", anyOf("MANAGE_SCHEME")),
            rule(POST, "/group-life/events/*/decision", anyOf("UNDERWRITE_MEMBER")),
            rule(POST, "/group-life/schemes/*/credit-loans", anyOf("PROCESS_CENSUS")),
            rule(POST, "/group-life/credit-loans/*/settlement", anyOf("BILL_SCHEME")),

            // ── shared services: party merges, wordings, extracts, governed assistants ─────────────────
            rule(GET, "/platform/parties/**", anyOf("READ_CUSTOMER")),
            rule(POST, "/platform/parties/merges", anyOf("KYC_CUSTOMER")),
            rule(GET, "/platform/wordings", anyOf("READ_PRODUCT")),
            rule(POST, "/platform/wordings", anyOf("UPDATE_PRODUCT")),
            rule(POST, "/platform/wordings/*/versions/*/approval", anyOf("ACTIVATE_PRODUCT")),
            rule(GET, "/platform/metrics", anyOf("READ_REPORT")),
            rule(GET, "/platform/extracts", anyOf("READ_REPORT")),
            rule(POST, "/platform/extracts", anyOf("READ_REPORT")),
            rule(GET, "/platform/assistants/log", anyOf("READ_AUDIT")),
            rule(GET, "/platform/assistants", staff()),
            rule(PUT, "/platform/assistants/*", anyOf("MANAGE_CONFIG")),
            rule(POST, "/platform/assistants/**", staff()),

            // ── partner portals, work queue, document register, payouts ────────────────────────────────
            rule(GET, "/ops/portal", anyOf("USE_PARTNER_PORTAL")),
            rule(POST, "/ops/portal/requests", anyOf("USE_PARTNER_PORTAL")),
            rule(GET, "/ops/portal-accounts", anyOf("READ_USER")),
            rule(PUT, "/ops/portal-accounts", anyOf("UPDATE_USER")),
            rule(GET, "/ops/portal-requests", staff()),
            rule(POST, "/ops/portal-requests/*/close", staff()),
            rule(GET, "/ops/workflow", staff()),
            rule(PUT, "/ops/workflow/**", anyOf("MANAGE_CONFIG")),
            rule(GET, "/ops/queue", staff()),
            rule(POST, "/ops/queue/claim", staff()),
            rule(DELETE, "/ops/queue/claim", staff()),
            rule(POST, "/ops/queue/assign", anyOf("ASSIGN_WORK")),
            rule(GET, "/ops/queue/assignees", anyOf("ASSIGN_WORK")),
            rule(GET, "/ops/documents", anyOf("READ_POLICY")),
            rule(PUT, "/ops/documents/hold", anyOf("UPDATE_POLICY")),
            rule(PUT, "/ops/documents/retention", anyOf("MANAGE_CONFIG")),
            rule(GET, "/ops/payouts", anyOf("READ_CLAIM")),

            // ── reinsurance SH07 (Reinsurance Officer) ─────────────────────────────────────────────────
            rule(GET, "/reinsurance/**", anyOf("READ_REINSURANCE")),
            rule(POST, "/reinsurance/treaties", anyOf("MANAGE_REINSURANCE")),
            rule(POST, "/reinsurance/treaties/*/approve", anyOf("APPROVE_TREATY")),
            rule(POST, "/reinsurance/bordereaux", anyOf("MANAGE_REINSURANCE")),

            // ── communications SH09B (Service Agent, Operations Manager) ───────────────────────────────
            rule(GET, "/communications/**", anyOf("READ_COMMUNICATION")),
            rule(POST, "/communications/templates", anyOf("MANAGE_COMMUNICATION")),
            rule(POST, "/communications/templates/*/approve", anyOf("APPROVE_TEMPLATE")),
            rule(POST, "/communications/messages", anyOf("MANAGE_COMMUNICATION")),
            rule(POST, "/communications/messages/*/retry", anyOf("MANAGE_COMMUNICATION")),
            rule(POST, "/communications/dispatch", anyOf("MANAGE_COMMUNICATION")),
            rule(PUT, "/communications/preferences/*", anyOf("MANAGE_COMMUNICATION")),
            rule(POST, "/communications/inbound/*/assign", anyOf("MANAGE_COMMUNICATION")),
            rule(POST, "/communications/bulk-notices", anyOf("MANAGE_COMMUNICATION")),
            rule(POST, "/communications/bulk-notices/*/approve", anyOf("APPROVE_TEMPLATE")),
            rule(POST, "/communications/bulk-notices/*/reject", anyOf("APPROVE_TEMPLATE")),
            rule(POST, "/communications/reminders/run", anyOf("MANAGE_COMMUNICATION")),

            // ── service desk and complaints SH10 (Service Agent) ───────────────────────────────────────
            rule(GET, "/service/cases", anyOf("READ_SERVICE_CASE")),
            rule(GET, "/service/cases/*", anyOf("READ_SERVICE_CASE")),
            rule(POST, "/service/cases", anyOf("MANAGE_SERVICE_CASE")),
            rule(POST, "/service/cases/*/*", anyOf("MANAGE_SERVICE_CASE")),
            rule(GET, "/portfolio/shop/cases", customer()),
            rule(POST, "/portfolio/shop/cases", customer()),

            // ── collections SH04: billing accounts for anyone who reads policies or finance; changes by Finance ──
            rule(GET, "/collections/policies/*", anyOf("READ_POLICY", "READ_FINANCE")),
            rule(POST, "/collections/receipts/*/reverse", anyOf("MANAGE_FINANCE")),
            rule(POST, "/collections/instalments/*/adjustments", anyOf("MANAGE_FINANCE")),
            rule(POST, "/collections/billing-run", anyOf("MANAGE_FINANCE")),
            rule(POST, "/collections/statements", anyOf("MANAGE_FINANCE")),
            rule(GET, "/collections/statements", anyOf("READ_FINANCE")),
            rule(GET, "/collections/statements/*/items", anyOf("READ_FINANCE")),
            rule(GET, "/collections/reconciliation", anyOf("READ_FINANCE")),
            rule(POST, "/collections/reconciliation/*/assign", anyOf("MANAGE_FINANCE")),
            rule(POST, "/collections/reconciliation/*/resolve", anyOf("MANAGE_FINANCE")),

            // ── integration and operational control SH12 (Operations Manager) ──────────────────────────
            rule(GET, "/integration/**", anyOf("READ_INTEGRATION")),
            rule(POST, "/integration/outbox/dispatch", anyOf("MANAGE_INTEGRATION")),
            rule(POST, "/integration/deliveries/*/replay", anyOf("MANAGE_INTEGRATION")),
            rule(POST, "/integration/payment-notifications", anyOf("MANAGE_INTEGRATION")),
            rule(POST, "/integration/simulator/provider-receipts", anyOf("MANAGE_INTEGRATION")),

            // ── management reporting SH13 ──────────────────────────────────────────────────────────────
            rule(GET, "/reports/**", anyOf("READ_REPORT")),

            // ── insurer installation and configuration releases (guide §12, §16) ───────────────────────
            // GET /public/config is covered by /public/** above: branding only, nothing secret.
            rule(GET, "/admin/deployment", anyOf("READ_CONFIG")),
            rule(GET, "/admin/config-releases", anyOf("READ_CONFIG")),
            rule(GET, "/admin/config-releases/*", anyOf("READ_CONFIG")),
            rule(POST, "/admin/config-releases", anyOf("MANAGE_CONFIG")),
            rule(PUT, "/admin/config-releases/*", anyOf("MANAGE_CONFIG")),
            rule(POST, "/admin/config-releases/*/submit", anyOf("MANAGE_CONFIG")),
            rule(POST, "/admin/config-releases/*/approve", anyOf("APPROVE_CONFIG")),
            rule(POST, "/admin/config-releases/*/reject", anyOf("APPROVE_CONFIG")),
            rule(POST, "/admin/config-releases/*/activate", anyOf("ACTIVATE_CONFIG")),
            rule(GET, "/configuration/forms/policy", staff()),
            rule(GET, "/portfolio/policies/*/attributes", anyOf("READ_POLICY")),
            rule(PUT, "/portfolio/policies/*/attributes", anyOf("UPDATE_POLICY")),

            // ── access administration (docs/IMPLEMENT_RBAC.md "Role administration API") ──────────────────────
            // No role holds these codes by default (spec 04 A-18), so in practice only Super Admin manages access;
            // granting them to another role delegates it. The services re-check the same codes.
            rule(GET, "/users", anyOf("READ_USER")),
            rule(GET, "/users/*", anyOf("READ_USER")),
            rule(POST, "/users", anyOf("CREATE_USER")),
            rule(PUT, "/users/*", anyOf("UPDATE_USER")),
            rule(DELETE, "/users/*", anyOf("DELETE_USER")),
            rule(GET, "/roles", anyOf("READ_ROLE")),
            rule(GET, "/roles/*", anyOf("READ_ROLE")),
            rule(POST, "/roles", anyOf("CREATE_ROLE")),
            rule(PUT, "/roles/*", anyOf("UPDATE_ROLE")),
            rule(PUT, "/roles/*/permissions", anyOf("PERMISSIONS_ROLE")),
            rule(DELETE, "/roles/*", anyOf("DELETE_ROLE")),
            // The permission vocabulary is closed and lives in source (PermissionCatalog, spec 04 A-4): codes can be
            // read and relabelled, never created or deleted through the API.
            rule(GET, "/permissions", anyOf("READ_PERMISSION")),
            rule(GET, "/permissions/*", anyOf("READ_PERMISSION")),
            rule(PUT, "/permissions/*", anyOf("UPDATE_PERMISSION")),
            // Claim approval limits per role (spec 04 §5.3): part of administering roles.
            rule(GET, "/admin/approval-authorities", anyOf("READ_ROLE")),
            rule(PUT, "/admin/approval-authorities/*", anyOf("UPDATE_ROLE")),
            rule(DELETE, "/admin/approval-authorities/*", anyOf("UPDATE_ROLE")));

    private static ApiRule rule(HttpMethod method, String pattern, ApiAccess access) {
        return new ApiRule(method, pattern, access);
    }
}
