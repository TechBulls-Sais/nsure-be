package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
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

    @PutMapping("/claims/{ref}")
    public PortfolioViews.ClaimView updateClaim(@PathVariable String ref, @RequestBody JsonNode body) {
        return portfolio.updateClaim(ref, body);
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
