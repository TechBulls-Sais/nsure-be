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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/portfolio")
public class PortfolioController {

    private final PortfolioService portfolio;

    public PortfolioController(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    @GetMapping("/customers")
    public List<PortfolioViews.CustomerView> customers() {
        return portfolio.customers();
    }

    @PostMapping("/customers")
    public PortfolioViews.CustomerView createCustomer(@RequestBody JsonNode body) {
        return portfolio.createCustomer(body);
    }

    @PutMapping("/customers/{ref}")
    public PortfolioViews.CustomerView updateCustomer(@PathVariable String ref, @RequestBody JsonNode body) {
        return portfolio.updateCustomer(ref, body);
    }

    /** KYC review: no body (or decision VERIFIED) approves; decision FAILED needs a reason. */
    @PostMapping("/customers/{ref}/kyc")
    public PortfolioViews.CustomerView reviewKyc(@PathVariable String ref, @RequestBody(required = false) JsonNode body) {
        return portfolio.reviewKyc(ref, body);
    }

    @PostMapping("/customers/{ref}/edd")
    public PortfolioViews.CustomerView reviewEdd(@PathVariable String ref, @RequestBody(required = false) JsonNode body) {
        return portfolio.reviewEdd(ref, body);
    }

    // ── policy workflow ───────────────────────────────────────────────────────────────────────────

    @PostMapping("/policies/{ref}/payments")
    public PortfolioViews.CoverView recordPayment(@PathVariable String ref, @RequestBody(required = false) JsonNode body) {
        return portfolio.recordPayment(ref, body);
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

    @GetMapping("/history/{type}/{ref}")
    public List<PortfolioViews.HistoryView> history(@PathVariable String type, @PathVariable String ref) {
        return portfolio.history(type, ref);
    }

    @GetMapping("/applications")
    public List<PortfolioViews.CoverView> applications() {
        return portfolio.applications();
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

    @PostMapping("/policies")
    public PortfolioViews.CoverView createPolicy(@RequestBody JsonNode body) {
        return portfolio.createPolicy(body);
    }

    @GetMapping("/claims")
    public List<PortfolioViews.ClaimView> claims() {
        return portfolio.claims();
    }

    @PostMapping("/claims")
    public PortfolioViews.ClaimView createClaim(@RequestBody JsonNode body) {
        return portfolio.createClaim(body);
    }

    // ── claim workflow ────────────────────────────────────────────────────────────────────────────

    @PutMapping("/claims/{ref}/payee")
    public PortfolioViews.ClaimView setClaimPayee(@PathVariable String ref, @RequestBody JsonNode body) {
        return portfolio.setClaimPayee(ref, body);
    }

    @PostMapping("/claims/{ref}/checks")
    public PortfolioViews.ClaimView rerunClaimChecks(@PathVariable String ref) {
        return portfolio.rerunClaimChecks(ref);
    }

    /** assess, approve ({payoutAmount}), reject ({reason}) or pay. */
    @PostMapping("/claims/{ref}/{action:assess|approve|reject|pay}")
    public PortfolioViews.ClaimView claimAction(
            @PathVariable String ref, @PathVariable String action, @RequestBody(required = false) JsonNode body) {
        return portfolio.claimAction(ref, action, body);
    }

    @GetMapping("/products")
    public List<PortfolioViews.ProductView> products() {
        return portfolio.products();
    }

    @GetMapping("/schemes")
    public List<PortfolioViews.SchemeView> schemes() {
        return portfolio.schemes();
    }

    @GetMapping("/receipts")
    public List<PortfolioViews.CoverView> receipts() {
        return portfolio.receipts();
    }

    @PutMapping("/receipts/{ref}")
    public PortfolioViews.CoverView updateReceipt(@PathVariable String ref, @RequestBody JsonNode body) {
        return portfolio.updateReceipt(ref, body);
    }

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
}
