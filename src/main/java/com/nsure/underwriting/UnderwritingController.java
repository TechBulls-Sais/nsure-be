package com.nsure.underwriting;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** IL02 Individual underwriting: the queue, a case with its evidence and decisions, rules and authority limits. */
@RestController
@RequestMapping("/underwriting")
public class UnderwritingController {

    private final UnderwritingService underwriting;

    public UnderwritingController(UnderwritingService underwriting) {
        this.underwriting = underwriting;
    }

    @GetMapping("/cases")
    public List<UnderwritingViews.CaseView> cases(@RequestParam(required = false) String status) {
        return underwriting.cases(status);
    }

    @GetMapping("/summary")
    public UnderwritingViews.Summary summary() {
        return underwriting.summary();
    }

    @GetMapping("/cases/{ref}")
    public UnderwritingViews.CaseDetail detail(@PathVariable String ref) {
        return underwriting.detail(ref);
    }

    @GetMapping("/applications/{applicationRef}")
    public UnderwritingViews.CaseDetail forApplication(@PathVariable String applicationRef) {
        return underwriting.detailForApplication(applicationRef);
    }

    /** {lifeName?, requirement, restricted?, dueDate?} */
    @PostMapping("/cases/{ref}/evidence")
    public UnderwritingViews.CaseDetail requestEvidence(@PathVariable String ref, @RequestBody JsonNode body) {
        return underwriting.requestEvidence(ref, body);
    }

    /** {status RECEIVED|WAIVED, note} */
    @PostMapping("/cases/{ref}/evidence/{id}/resolve")
    public UnderwritingViews.CaseDetail resolveEvidence(@PathVariable String ref, @PathVariable Long id,
            @RequestBody JsonNode body) {
        return underwriting.resolveEvidence(ref, id, body);
    }

    /** {decision, loadingPercent?, exclusion?, postponeUntil?, reason} */
    @PostMapping("/cases/{ref}/decision")
    public UnderwritingViews.CaseDetail decide(@PathVariable String ref, @RequestBody JsonNode body) {
        return underwriting.decide(ref, body);
    }

    /** {accept, note} */
    @PostMapping("/cases/{ref}/offer")
    public UnderwritingViews.CaseDetail answerOffer(@PathVariable String ref, @RequestBody JsonNode body) {
        return underwriting.answerOffer(ref, body);
    }

    /** {reason} */
    @PostMapping("/cases/{ref}/reopen")
    public UnderwritingViews.CaseDetail reopen(@PathVariable String ref, @RequestBody JsonNode body) {
        return underwriting.reopen(ref, body);
    }

    /** {outcome CLEARED|CONFIRMED, note}: compliance's answer to a possible screening match. */
    @PostMapping("/cases/{ref}/screening")
    public UnderwritingViews.CaseDetail resolveScreening(@PathVariable String ref, @RequestBody JsonNode body) {
        return underwriting.resolveScreening(ref, body);
    }

    /** Runs the daily review now: expired decisions and ended postponements reopen, due evidence is reminded. */
    @PostMapping("/review-due")
    public java.util.Map<String, List<String>> reviewDue() {
        return underwriting.reviewDueNow();
    }

    /** {assignee} */
    @PostMapping("/cases/{ref}/assign")
    public UnderwritingViews.CaseDetail assign(@PathVariable String ref, @RequestBody JsonNode body) {
        return underwriting.assign(ref, body);
    }

    @GetMapping("/rules")
    public List<UnderwritingViews.RuleSetView> rules() {
        return underwriting.rules();
    }

    @PutMapping("/rules/{id}")
    public UnderwritingViews.RuleSetView updateRules(@PathVariable Long id, @RequestBody JsonNode body) {
        return underwriting.updateRules(id, body);
    }

    @GetMapping("/rule-changes")
    public List<UnderwritingViews.RuleChangeView> ruleChanges() {
        return underwriting.ruleChanges();
    }

    /** {approve, note}: a second person applies or rejects a proposed rule change. */
    @PostMapping("/rule-changes/{id}/decision")
    public UnderwritingViews.RuleChangeView decideRuleChange(@PathVariable Long id, @RequestBody JsonNode body) {
        return underwriting.decideRuleChange(id, body);
    }

    @GetMapping("/authorities")
    public List<UnderwritingViews.AuthorityView> authorities() {
        return underwriting.authorities();
    }

    /** {maxSumAssured}: blank removes the limit. */
    @PutMapping("/authorities/{roleId}")
    public List<UnderwritingViews.AuthorityView> setAuthority(@PathVariable Long roleId, @RequestBody JsonNode body) {
        return underwriting.setAuthority(roleId, body);
    }
}
