package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SH02 product release: the release checklist and manifest, the lifecycle actions (submit, approve, reject, activate,
 * withdraw, rollback), golden cases and signed configuration packages.
 */
@RestController
@RequestMapping("/portfolio")
public class ProductReleaseController {

    private final ProductRelease release;

    public ProductReleaseController(ProductRelease release) {
        this.release = release;
    }

    /** The typed rule catalogue: each rule's type, unit, range or values, and owner. */
    @GetMapping("/product-rules")
    public List<ProductRules.RuleDef> rules() {
        return release.ruleCatalog();
    }

    @GetMapping("/product-configs/{ref}/checklist")
    public ProductRelease.ChecklistView checklist(@PathVariable String ref) {
        return release.checklist(ref);
    }

    /** submit, approve ({note}), reject ({reason}), activate, withdraw ({reason}) or rollback ({version}). */
    @PostMapping("/product-configs/{ref}/{action:submit|approve|reject|activate|withdraw|rollback}")
    public PortfolioViews.ConfigView action(@PathVariable String ref, @PathVariable String action,
            @RequestBody(required = false) JsonNode body) {
        return switch (action) {
            case "submit" -> release.submit(ref);
            case "approve" -> release.approve(ref, text(body, "note"));
            case "reject" -> release.reject(ref, text(body, "reason"));
            case "activate" -> release.activate(ref);
            case "withdraw" -> release.withdraw(ref, text(body, "reason"));
            default -> release.rollback(ref, body == null ? 0 : body.path("version").asInt(0));
        };
    }

    /** Re-runs the golden cases against the configuration as it stands. */
    @PostMapping("/product-configs/{ref}/golden-cases/run")
    public List<ProductRelease.GoldenCaseView> runGolden(@PathVariable String ref) {
        return release.runGolden(ref);
    }

    /** {label, dateOfBirth, pricedOn?, expectedPremium (blank = must be refused), supersedes?, reason?}. */
    @PostMapping("/product-configs/{ref}/golden-cases")
    public ProductRelease.GoldenCaseView proposeGolden(@PathVariable String ref, @RequestBody JsonNode body) {
        return release.proposeGolden(ref, body);
    }

    @PostMapping("/product-configs/{ref}/golden-cases/{id}/{decision:approve|reject}")
    public ProductRelease.GoldenCaseView decideGolden(@PathVariable String ref, @PathVariable long id,
            @PathVariable String decision) {
        return release.decideGolden(ref, id, "approve".equals(decision));
    }

    /** The signed configuration package (content, rules, dependencies, golden cases, checksum, signature). */
    @GetMapping("/product-configs/{ref}/package")
    public ObjectNode exportPackage(@PathVariable String ref) {
        return release.exportPackage(ref);
    }

    /** Imports a package as a new draft; refused before anything is created when it fails verification. */
    @PostMapping("/product-configs/import")
    public PortfolioViews.ConfigView importPackage(@RequestBody JsonNode body) {
        return release.importPackage(body);
    }

    private static String text(JsonNode body, String field) {
        return body == null || !body.hasNonNull(field) ? null : body.get(field).asText();
    }
}
