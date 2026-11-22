package com.nsure.group;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plans, categories and benefits (GL02): product benefit rules, versioned scheme/category benefit terms with their
 * approval, the resolved rule set, category amendment impact and versions, and benefit cessation.
 */
@RestController
public class PlanController {

    private final BenefitRules rules;
    private final GroupSchemeService schemes;

    public PlanController(BenefitRules rules, GroupSchemeService schemes) {
        this.rules = rules;
        this.schemes = schemes;
    }

    @GetMapping("/group-life/product-rules")
    public List<Map<String, Object>> productRules() {
        return rules.productRules();
    }

    @GetMapping("/group-life/schemes/{ref}/benefit-terms")
    public List<Map<String, Object>> terms(@PathVariable String ref) {
        return rules.terms(ref);
    }

    /** {benefitType, categoryCode?, included?, parameters {..}, effectiveFrom?, reason?} */
    @PutMapping("/group-life/schemes/{ref}/benefit-terms")
    public Map<String, Object> saveTerms(@PathVariable String ref, @RequestBody JsonNode body) {
        return rules.saveTerms(ref, body);
    }

    /** {note}: a second person approves every plan change waiting on the scheme. */
    @PostMapping("/group-life/schemes/{ref}/plan-approval")
    public List<Map<String, Object>> approvePlan(@PathVariable String ref, @RequestBody JsonNode body) {
        return rules.approvePlan(ref, body);
    }

    @GetMapping("/group-life/schemes/{ref}/resolved-benefits")
    public Map<String, Object> resolved(@PathVariable String ref) {
        return rules.resolvedView(ref);
    }

    /** The category amendment's impact with nothing changed: the proposed category body. */
    @PostMapping("/group/schemes/{ref}/categories/{code}/impact")
    public Map<String, Object> categoryImpact(@PathVariable String ref, @PathVariable String code,
            @RequestBody JsonNode body) {
        return schemes.previewCategoryChange(ref, code, body);
    }

    @GetMapping("/group/schemes/{ref}/categories/{code}/versions")
    public List<Map<String, Object>> categoryVersions(@PathVariable String ref, @PathVariable String code) {
        return schemes.categoryVersions(ref, code);
    }

    @GetMapping("/group/schemes/{ref}/cessations")
    public List<Map<String, Object>> cessations(@PathVariable String ref, @RequestParam(required = false) String asAt) {
        return schemes.cessations(ref, asAt);
    }

    /** {asAt?} */
    @PostMapping("/group/schemes/{ref}/cessations")
    public List<Map<String, Object>> applyCessations(@PathVariable String ref, @RequestBody(required = false) JsonNode body) {
        return schemes.applyCessations(ref, body);
    }
}
