package com.nsure.group;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Group administration GL01-GL05: schemes, categories, census, member underwriting and scheme billing. */
@RestController
@RequestMapping("/group")
public class GroupController {

    private final GroupSchemeService schemes;
    private final CensusService census;
    private final SchemeBillingService billing;
    private final GroupClaimService claims;

    public GroupController(GroupSchemeService schemes, CensusService census, SchemeBillingService billing,
            GroupClaimService claims) {
        this.schemes = schemes;
        this.census = census;
        this.billing = billing;
        this.claims = claims;
    }

    @GetMapping("/schemes")
    public List<GroupViews.SchemeView> schemes() {
        return schemes.schemes();
    }

    @GetMapping("/schemes/{ref}")
    public GroupViews.SchemeDetailView scheme(@PathVariable String ref) {
        return schemes.scheme(ref);
    }

    @PostMapping("/schemes")
    public GroupViews.SchemeDetailView createScheme(@RequestBody JsonNode body) {
        return schemes.createScheme(body);
    }

    /** Refused once the scheme has any contractual or financial activity: such a scheme is closed, never deleted. */
    @DeleteMapping("/schemes/{ref}")
    public void deleteScheme(@PathVariable String ref) {
        schemes.deleteScheme(ref);
    }

    @PostMapping("/schemes/{ref}/categories")
    public GroupViews.CategoryView addCategory(@PathVariable String ref, @RequestBody JsonNode body) {
        return schemes.addCategory(ref, body);
    }

    @PutMapping("/schemes/{ref}/categories/{code}")
    public GroupViews.CategoryView updateCategory(@PathVariable String ref, @PathVariable String code,
            @RequestBody JsonNode body) {
        return schemes.updateCategory(ref, code, body);
    }

    @PostMapping("/schemes/{ref}/census-batches")
    public GroupViews.CensusBatchView uploadCensus(@PathVariable String ref, @RequestBody JsonNode body) {
        return census.upload(ref, body);
    }

    @GetMapping("/census-batches/{ref}")
    public GroupViews.CensusBatchView censusBatch(@PathVariable String ref) {
        return census.batch(ref);
    }

    @GetMapping("/census-batches/{ref}/reconciliation")
    public java.util.Map<String, Object> censusReconciliation(@PathVariable String ref) {
        return census.reconciliation(ref);
    }

    @PostMapping("/census-batches/{ref}/commit")
    public GroupViews.CensusBatchView commitCensus(@PathVariable String ref) {
        return census.commit(ref);
    }

    /** The published, versioned census template (NS-GL04-001). */
    @GetMapping("/census-template")
    public java.util.Map<String, Object> censusTemplate() {
        return census.template();
    }

    /** {decision APPLY|REJECT, note}: the authorised review of a held census row (AT-GL04-03). */
    @PostMapping("/census-batches/{ref}/rows/{lineNo}/review")
    public GroupViews.CensusBatchView reviewCensusRow(@PathVariable String ref, @PathVariable int lineNo,
            @RequestBody JsonNode body) {
        return census.review(ref, lineNo, body);
    }

    /** A member's effective-dated cover periods (NS-GL04-004). */
    @GetMapping("/members/{id}/cover-history")
    public List<java.util.Map<String, Object>> coverHistory(@PathVariable Long id) {
        return census.coverHistory(id);
    }

    @GetMapping("/underwriting-queue")
    public List<java.util.Map<String, Object>> underwritingQueue() {
        return schemes.underwritingQueue();
    }

    @GetMapping("/arrears")
    public List<java.util.Map<String, Object>> arrears() {
        return billing.arrears();
    }

    @PostMapping("/members/{id}/underwriting")
    public GroupViews.MemberView underwrite(@PathVariable Long id, @RequestBody JsonNode body) {
        return schemes.underwrite(id, body);
    }

    @PostMapping("/schemes/{ref}/invoices")
    public GroupViews.InvoiceView invoice(@PathVariable String ref, @RequestBody JsonNode body) {
        return billing.invoice(ref, body);
    }

    @GetMapping("/schemes/{ref}/claims")
    public List<GroupViews.ClaimView> claims(@PathVariable String ref) {
        return claims.claims(ref);
    }

    /** {memberKey, benefitType, eventDate, amount, note?} */
    @PostMapping("/schemes/{ref}/claims")
    public GroupViews.ClaimView notifyClaim(@PathVariable String ref, @RequestBody JsonNode body) {
        return claims.notify(ref, body);
    }

    /** {action APPROVE|DECLINE|PAY|REVIEW|CEASE, amount?, note, nextReviewDate?, paymentReference?} */
    @PostMapping("/claims/{ref}/decision")
    public GroupViews.ClaimView decideClaim(@PathVariable String ref, @RequestBody JsonNode body) {
        return claims.decide(ref, body);
    }

    /** {memberKey, action RAISE|RESOLVE, note} */
    @PostMapping("/invoices/{ref}/disputes")
    public GroupViews.InvoiceView dispute(@PathVariable String ref, @RequestBody JsonNode body) {
        return billing.dispute(ref, body);
    }

    @PostMapping("/invoices/{ref}/receipts")
    public GroupViews.ReceiptAllocationView receipt(@PathVariable String ref, @RequestBody JsonNode body) {
        return billing.receipt(ref, body);
    }
}
