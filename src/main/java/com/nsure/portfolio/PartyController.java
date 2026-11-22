package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The party master workspace (SH01): duplicates and merges, KYC, contacts, relationships and the customer view. */
@RestController
@RequestMapping("/portfolio")
public class PartyController {

    private final PartyService parties;
    private final PortfolioService portfolio;

    public PartyController(PartyService parties, PortfolioService portfolio) {
        this.parties = parties;
        this.portfolio = portfolio;
    }

    @GetMapping("/parties/duplicates")
    public List<Map<String, Object>> duplicates() {
        return parties.duplicates();
    }

    @GetMapping("/parties/merge-requests")
    public List<Map<String, Object>> mergeRequests() {
        return parties.mergeRequests();
    }

    @PostMapping("/parties/merge-requests")
    public Map<String, Object> requestMerge(@RequestBody JsonNode body) {
        return parties.requestMerge(body);
    }

    @PostMapping("/parties/merge-requests/{ref}/approve")
    public Map<String, Object> approveMerge(@PathVariable String ref, @RequestBody(required = false) JsonNode body) {
        return parties.approveMerge(ref, body);
    }

    @PostMapping("/parties/merge-requests/{ref}/reject")
    public Map<String, Object> rejectMerge(@PathVariable String ref, @RequestBody(required = false) JsonNode body) {
        return parties.rejectMerge(ref, body);
    }

    @GetMapping("/parties/resolve/{ref}")
    public Map<String, Object> resolve(@PathVariable String ref) {
        return parties.resolve(ref);
    }

    @GetMapping("/customers/{ref}/view")
    public Map<String, Object> view(@PathVariable String ref) {
        return parties.view(ref);
    }

    @GetMapping("/customers/{ref}/kyc-record")
    public Map<String, Object> kyc(@PathVariable String ref) {
        return parties.kyc(ref);
    }

    @PostMapping("/customers/{ref}/screening")
    public Map<String, Object> screen(@PathVariable String ref) {
        return parties.screen(ref);
    }

    @PostMapping("/customers/{ref}/screening/review")
    public Map<String, Object> reviewHit(@PathVariable String ref, @RequestBody JsonNode body) {
        return parties.reviewHit(ref, body);
    }

    @GetMapping("/customers/{ref}/contacts")
    public Map<String, Object> contacts(@PathVariable String ref) {
        return parties.contacts(ref);
    }

    @PostMapping("/customers/{ref}/addresses")
    public Map<String, Object> addAddress(@PathVariable String ref, @RequestBody JsonNode body) {
        return parties.addAddress(ref, body);
    }

    @PostMapping("/customers/{ref}/identifiers")
    public Map<String, Object> addIdentifier(@PathVariable String ref, @RequestBody JsonNode body) {
        return parties.addIdentifier(ref, body);
    }

    @PostMapping("/customers/{ref}/contact-changes")
    public Map<String, Object> requestContactChange(@PathVariable String ref, @RequestBody JsonNode body) {
        return parties.requestContactChange(ref, body);
    }

    @PostMapping("/contact-changes/{ref}/verify")
    public Map<String, Object> verifyContactChange(@PathVariable String ref, @RequestBody JsonNode body) {
        return parties.verifyContactChange(ref, body, null);
    }

    @PostMapping("/contact-changes/{ref}/approve")
    public Map<String, Object> approveContactChange(@PathVariable String ref,
            @RequestBody(required = false) JsonNode body) {
        return parties.decideContactChange(ref, true, body);
    }

    @PostMapping("/contact-changes/{ref}/reject")
    public Map<String, Object> rejectContactChange(@PathVariable String ref, @RequestBody JsonNode body) {
        return parties.decideContactChange(ref, false, body);
    }

    @GetMapping("/customers/{ref}/relationships")
    public Map<String, Object> relationships(@PathVariable String ref) {
        return parties.relationships(ref);
    }

    @PostMapping("/customers/{ref}/relationships")
    public Map<String, Object> addRelationship(@PathVariable String ref, @RequestBody JsonNode body) {
        return parties.addRelationship(ref, body);
    }

    @PostMapping("/relationships/{ref}/end")
    public Map<String, Object> endRelationship(@PathVariable String ref, @RequestBody JsonNode body) {
        return parties.endRelationship(ref, body);
    }

    // ── the customer portal: own contact changes and delegated access ─────────────────────────────────

    @PostMapping("/shop/contact-changes")
    public Map<String, Object> requestOwnContactChange(@RequestBody JsonNode body) {
        return parties.requestOwnContactChange(portfolio.customerForLogin(), body);
    }

    @PostMapping("/shop/contact-changes/{ref}/verify")
    public Map<String, Object> verifyOwnContactChange(@PathVariable String ref, @RequestBody JsonNode body) {
        return parties.verifyContactChange(ref, body, portfolio.customerForLogin());
    }

    @GetMapping("/shop/delegated")
    public List<Map<String, Object>> delegated() {
        return parties.delegatedTo(portfolio.customerForLogin());
    }

    @GetMapping("/shop/delegated/{policyRef}")
    public Map<String, Object> delegatedPolicy(@PathVariable String policyRef) {
        return parties.delegatedPolicy(portfolio.customerForLogin(), policyRef);
    }
}
