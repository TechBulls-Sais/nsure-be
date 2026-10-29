package com.nsure.group;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
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

    public GroupController(GroupSchemeService schemes, CensusService census, SchemeBillingService billing) {
        this.schemes = schemes;
        this.census = census;
        this.billing = billing;
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

    @PostMapping("/census-batches/{ref}/commit")
    public GroupViews.CensusBatchView commitCensus(@PathVariable String ref) {
        return census.commit(ref);
    }

    @PostMapping("/members/{id}/underwriting")
    public GroupViews.MemberView underwrite(@PathVariable Long id, @RequestBody JsonNode body) {
        return schemes.underwrite(id, body);
    }

    @PostMapping("/schemes/{ref}/invoices")
    public GroupViews.InvoiceView invoice(@PathVariable String ref, @RequestBody JsonNode body) {
        return billing.invoice(ref, body);
    }

    @PostMapping("/invoices/{ref}/receipts")
    public GroupViews.ReceiptAllocationView receipt(@PathVariable String ref, @RequestBody JsonNode body) {
        return billing.receipt(ref, body);
    }
}
