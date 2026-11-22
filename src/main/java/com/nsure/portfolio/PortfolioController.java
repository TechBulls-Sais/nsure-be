package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/portfolio")
public class PortfolioController {

    private final PortfolioService portfolio;
    private final ProductTerms productTerms;

    public PortfolioController(PortfolioService portfolio, ProductTerms productTerms) {
        this.portfolio = portfolio;
        this.productTerms = productTerms;
    }

    // ── versioned product terms (NS-SH02-003/004): propose, a second person approves, new business only ──────

    @GetMapping("/products/{product}/versions")
    public List<ProductTerms.VersionView> productVersions(@PathVariable String product) {
        return productTerms.versions(product);
    }

    @PostMapping("/products/{product}/versions")
    public ProductTerms.VersionView proposeProductVersion(@PathVariable String product, @RequestBody JsonNode body) {
        return productTerms.propose(product, body);
    }

    @PostMapping("/products/{product}/versions/{version}/approve")
    public ProductTerms.VersionView approveProductVersion(@PathVariable String product, @PathVariable int version) {
        return productTerms.approve(product, version);
    }

    @PostMapping("/products/{product}/versions/{version}/reject")
    public ProductTerms.VersionView rejectProductVersion(@PathVariable String product, @PathVariable int version,
            @RequestBody JsonNode body) {
        return productTerms.reject(product, version, body);
    }

    /** The product terms a policy was issued under, whatever the product says today. */
    @GetMapping("/policies/{ref}/terms")
    public ProductTerms.VersionView policyTerms(@PathVariable String ref) {
        return productTerms.policyTerms(ref);
    }

    @GetMapping("/customers")
    public List<PortfolioViews.CustomerView> customers(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String search) {
        return portfolio.customers(search);
    }

    @PostMapping("/customers")
    public PortfolioViews.CustomerView createCustomer(@RequestBody JsonNode body) {
        return portfolio.createCustomer(body);
    }

    /** KYC review: no body (or decision VERIFIED) approves; decision FAILED needs a reason. */
    @PostMapping("/customers/{ref}/kyc")
    public PortfolioViews.CustomerView reviewKyc(@PathVariable String ref, @RequestBody(required = false) JsonNode body) {
        return portfolio.reviewKyc(ref, body);
    }

    @GetMapping("/shop")
    public PortfolioViews.ShopView shop() {
        return portfolio.shop();
    }

    @PostMapping("/shop")
    public PortfolioViews.ShopView buy(@RequestBody JsonNode body) {
        return portfolio.buy(body);
    }

    @PostMapping("/customers/{ref}/edd")
    public PortfolioViews.CustomerView reviewEdd(@PathVariable String ref, @RequestBody JsonNode body) {
        return portfolio.reviewEdd(ref, body);
    }

    // ── customer portal: the signed-in customer's own policies and claims ─────────────────────────

    // Declaration and consent (spec 01 BR-5, BR-6): a 6-digit code, four consents, and a lock after wrong codes.
    @GetMapping("/shop/declaration")
    public DeclarationService.LockState shopDeclarationLock() {
        return portfolio.shopDeclarationLock();
    }

    @PostMapping("/shop/policies/{ref}/declaration/otp")
    public DeclarationService.OtpSent shopDeclarationOtp(@PathVariable String ref) {
        return portfolio.shopDeclarationOtp(ref);
    }

    @PostMapping("/shop/policies/{ref}/declaration")
    public PortfolioService.ConsentView shopDeclare(@PathVariable String ref, @RequestBody JsonNode body) {
        return portfolio.shopDeclare(ref, body);
    }

    /** Read-only evidence (spec 04 A-15): audit.read. */
    @GetMapping("/policies/{ref}/declarations")
    public java.util.List<PortfolioService.ConsentView> declarations(@PathVariable String ref) {
        return portfolio.declarations(ref);
    }

    @PostMapping("/shop/policies/{ref}/pay")
    public PortfolioViews.ShopView shopPay(@PathVariable String ref, @RequestBody JsonNode body) {
        return portfolio.shopPay(ref, body);
    }

    @PostMapping("/shop/policies/{ref}/beneficiaries")
    public PortfolioViews.ShopView shopAddBeneficiary(@PathVariable String ref, @RequestBody JsonNode body) {
        return portfolio.shopAddBeneficiary(ref, body);
    }

    @DeleteMapping("/shop/policies/{ref}/beneficiaries/{id}")
    public PortfolioViews.ShopView shopRemoveBeneficiary(@PathVariable String ref, @PathVariable Long id) {
        return portfolio.shopRemoveBeneficiary(ref, id);
    }

    @PostMapping("/shop/claims")
    public PortfolioViews.ShopView shopClaim(@RequestBody JsonNode body) {
        return portfolio.shopClaim(body);
    }

    /** The disclosure questions the portal asks for a product (NS-IL01-003). */
    @GetMapping("/shop/questions")
    public List<PortfolioViews.DisclosureItem> shopQuestions(@RequestParam String product) {
        return portfolio.shopQuestions(product);
    }

    /** {accept}: the customer's answer to an underwriting offer on their own application. */
    @PostMapping("/shop/applications/{ref}/offer")
    public PortfolioViews.ShopView shopAnswerOffer(@PathVariable String ref, @RequestBody JsonNode body) {
        return portfolio.shopAnswerOffer(ref, body);
    }

    // ── policy workflow (staff) ───────────────────────────────────────────────────────────────────

    @PostMapping("/policies/{ref}/payments")
    public PortfolioViews.CoverView recordPayment(@PathVariable String ref, @RequestBody(required = false) JsonNode body) {
        return portfolio.recordPayment(ref, body == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : body);
    }

    @PostMapping("/policies/{ref}/cancel")
    public PortfolioViews.CoverView cancelPolicy(@PathVariable String ref, @RequestBody JsonNode body) {
        return portfolio.cancelPolicy(ref, body);
    }

    @PostMapping("/policies/{ref}/beneficiaries")
    public PortfolioViews.CoverView addBeneficiary(@PathVariable String ref, @RequestBody JsonNode body) {
        return portfolio.addBeneficiary(ref, body);
    }

    @DeleteMapping("/policies/{ref}/beneficiaries/{id}")
    public PortfolioViews.CoverView removeBeneficiary(@PathVariable String ref, @PathVariable Long id) {
        return portfolio.removeBeneficiary(ref, id);
    }

    /** Runs the hourly expiry and daily lapse sweeps now. */
    @PostMapping("/policies/sweep")
    public Map<String, List<String>> runPolicySweep() {
        return portfolio.runPolicySweep();
    }

    // The type is in the path pattern so ApiAccessRules can gate each history on its own read permission.
    @GetMapping("/history/{type:policy|claim|kyc|edd|application}/{ref}")
    public List<PortfolioViews.HistoryView> history(@PathVariable String type, @PathVariable String ref) {
        return portfolio.history(type, ref);
    }

    @PostMapping("/shop/quote")
    public PortfolioViews.QuoteView shopQuote(@RequestBody JsonNode body) {
        return portfolio.shopQuote(body);
    }

    @PostMapping("/quote")
    public PortfolioViews.QuoteView quote(@RequestBody JsonNode body) {
        return portfolio.quote(body);
    }

    @GetMapping("/applications")
    public List<PortfolioViews.CoverView> applications() {
        return portfolio.applications();
    }

    /** IL01 missing-evidence queue: every item still outstanding on an open application, with its due date. */
    @GetMapping("/applications/evidence-queue")
    public List<PortfolioViews.EvidenceQueueItem> evidenceQueue() {
        return portfolio.evidenceQueue();
    }

    /** The policy terms a product sells, in years (section 06 Term Life). */
    @GetMapping({"/application-terms", "/shop/terms"})
    public List<Integer> applicationTerms(@RequestParam String product) {
        return portfolio.applicationTerms(product);
    }

    /** The relationships a payer or proposer may have (NS-IL01-003). */
    @GetMapping("/application-relationships")
    public List<String> applicationRelationships() {
        return portfolio.applicationRelationships();
    }

    @PostMapping("/applications")
    public PortfolioViews.CoverView createApplication(@RequestBody JsonNode body) {
        return portfolio.createApplication(body);
    }

    @PutMapping("/applications/{ref}")
    public PortfolioViews.CoverView updateApplication(@PathVariable String ref, @RequestBody JsonNode body) {
        return portfolio.updateApplication(ref, body);
    }

    @GetMapping("/policies")
    public List<PortfolioViews.CoverView> policies() {
        return portfolio.policies();
    }

    /** Staff issue an offer; the first premium recorded on it issues cover. Idempotency-Key makes a resubmit safe. */
    @PostMapping("/policies")
    public PortfolioViews.CoverView createPolicy(@RequestBody JsonNode body,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false)
            String idempotencyKey) {
        return portfolio.createPolicy(body, idempotencyKey);
    }

    @GetMapping("/claims")
    public List<PortfolioViews.ClaimView> claims() {
        return portfolio.claims();
    }

    @PostMapping("/claims")
    public PortfolioViews.ClaimView createClaim(@RequestBody JsonNode body) {
        return portfolio.createClaim(body);
    }

    // ── claim workflow (staff) ────────────────────────────────────────────────────────────────────

    @PutMapping("/claims/{ref}/payee")
    public PortfolioViews.ClaimView setClaimPayee(@PathVariable String ref, @RequestBody JsonNode body) {
        return portfolio.setClaimPayee(ref, body);
    }

    @PostMapping("/claims/{ref}/checks")
    public PortfolioViews.ClaimView rerunClaimChecks(@PathVariable String ref) {
        return portfolio.rerunClaimChecks(ref);
    }

    /** {relatedRef, type (SAME_EVENT or SEPARATE_BENEFIT), note}: links another claim on the policy (NS-SH05-001). */
    @PostMapping("/claims/{ref}/links")
    public PortfolioViews.ClaimView linkClaim(@PathVariable String ref, @RequestBody JsonNode body) {
        return portfolio.linkClaim(ref, body);
    }

        /** A claim's reserve history and the benefit limits it draws on. */
    @GetMapping("/claims/{ref}/financials")
    public PortfolioViews.ClaimFinancialsView claimFinancials(@PathVariable String ref) {
        return portfolio.claimFinancials(ref);
    }

    /** assess, approve ({payoutAmount}), reject ({reason}), pay, confirm-payout ({providerReference}) or payout-failed ({reason}). */
    @PostMapping("/claims/{ref}/{action:assess|approve|reject|pay|confirm-payout|payout-failed|reopen|close}")
    public PortfolioViews.ClaimView claimAction(
            @PathVariable String ref, @PathVariable String action, @RequestBody(required = false) JsonNode body) {
        return portfolio.claimAction(ref, action, body);
    }

    // ── application capture (NS-IL01-002/003) ─────────────────────────────────────────────────────────

    @GetMapping("/applications/{ref}/checklist")
    public PortfolioViews.ApplicationChecklistView applicationChecklist(@PathVariable String ref) {
        return portfolio.applicationChecklist(ref);
    }

    /** The disclosure questions a product asks. */
    @GetMapping("/application-questions")
    public List<PortfolioViews.DisclosureItem> applicationQuestions(@RequestParam String product) {
        return portfolio.applicationQuestions(product);
    }

    /** Completes a draft: {proposerName?, payerName?, mandateMethod?, channel?, disclosures?, declarationAccepted?}. */
    @PutMapping("/applications/{ref}/details")
    public PortfolioViews.ApplicationChecklistView updateApplicationDetails(@PathVariable String ref, @RequestBody JsonNode body) {
        return portfolio.updateApplicationDetails(ref, body);
    }

    @PostMapping("/applications/{ref}/submit")
    public PortfolioViews.ApplicationChecklistView submitApplication(@PathVariable String ref) {
        return portfolio.submitApplication(ref);
    }

    /** {documentType, note}: evidence received and checked. */
    @PostMapping("/applications/{ref}/evidence")
    public PortfolioViews.ApplicationChecklistView recordApplicationEvidence(@PathVariable String ref, @RequestBody JsonNode body) {
        return portfolio.recordApplicationEvidence(ref, body);
    }

    // ── stored quotes (NS-IL01-001/004) ──────────────────────────────────────────────────────────────

    /** {customerRef, product, tier, lives?}: a priced, stored quote, not yet accepted. */
    @PostMapping("/quotes")
    public PortfolioViews.QuoteRecordView createQuote(@RequestBody JsonNode body) {
        return portfolio.createQuote(body);
    }

    @GetMapping("/quotes")
    public List<PortfolioViews.QuoteRecordView> quotes(@RequestParam(required = false) String customerRef) {
        return portfolio.quotes(customerRef);
    }

    /** Every version of the quote, newest first. */
    @GetMapping("/quotes/{ref}")
    public List<PortfolioViews.QuoteRecordView> quoteVersions(@PathVariable String ref) {
        return portfolio.quoteVersions(ref);
    }

    /** {version?, note?}: the customer accepted the latest offer. */
    @PostMapping("/quotes/{ref}/accept")
    public PortfolioViews.QuoteRecordView acceptQuote(@PathVariable String ref, @RequestBody(required = false) JsonNode body) {
        return portfolio.acceptQuote(ref, body);
    }

    /** Price it again from the current facts and rates: the next version, showing what changed. */
    @PostMapping("/quotes/{ref}/requote")
    public PortfolioViews.QuoteRecordView requote(@PathVariable String ref) {
        return portfolio.requote(ref);
    }

    @GetMapping("/quotes/{ref}/replay")
    public PortfolioViews.QuoteReplayView replayQuote(@PathVariable String ref, @RequestParam(required = false) Integer version) {
        return portfolio.replayQuote(ref, version);
    }

    @GetMapping("/products")
    public List<PortfolioViews.ProductView> products() {
        return portfolio.products();
    }

    /** Every plan of a product, saleable or withdrawn (section 06 product packs). */
    @GetMapping("/products/{product}/tiers")
    public List<PortfolioViews.TierRecordView> productTiers(@PathVariable String product) {
        return portfolio.productTiers(product);
    }

    /** {cover, premium, frequency?, popular?, benefits?, displayOrder?, active?}: adds or changes a plan. */
    @PutMapping("/products/{product}/tiers/{tier}")
    public List<PortfolioViews.TierRecordView> saveTier(@PathVariable String product, @PathVariable String tier,
            @RequestBody JsonNode body) {
        return portfolio.saveTier(product, tier, body);
    }

    @GetMapping("/schemes")
    public List<PortfolioViews.SchemeView> schemes() {
        return portfolio.schemes();
    }

    @GetMapping("/receipts")
    public List<PortfolioViews.CoverView> receipts() {
        return portfolio.receipts();
    }

    // There is deliberately no PUT /receipts/{ref}: a receipt is never edited (NS-SH04-005). It is created by a
    // collected premium and changed only by a recorded return or reversal (POST /collections/receipts/{ref}/reverse).

    @GetMapping("/product-configs")
    public List<PortfolioViews.ConfigView> productConfigs() {
        return portfolio.productConfigs();
    }

    @PostMapping("/product-configs")
    public PortfolioViews.ConfigView createConfig(@RequestBody JsonNode body) {
        return portfolio.saveConfig(null, body);
    }

    @PutMapping("/product-configs/{ref}")
    public PortfolioViews.ConfigView updateConfig(@PathVariable String ref, @RequestBody JsonNode body) {
        return portfolio.saveConfig(ref, body);
    }

    /** Every saved state of a configuration, newest first: nothing a save replaced is lost. */
    @GetMapping("/product-configs/{ref}/versions")
    public List<ProductConfigHistory.VersionView> configVersions(@PathVariable String ref) {
        return portfolio.configVersions(ref);
    }
}
