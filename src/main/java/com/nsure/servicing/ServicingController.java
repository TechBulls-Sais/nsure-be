package com.nsure.servicing;

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

/** IL03/IL04 policy servicing: endorsements, insured lives, contractual benefits, reinstatement and termination. */
@RestController
@RequestMapping("/servicing")
public class ServicingController {

    private final ServicingService servicing;

    public ServicingController(ServicingService servicing) {
        this.servicing = servicing;
    }

    @GetMapping("/summary")
    public ServicingViews.Summary summary() {
        return servicing.summary();
    }

    @GetMapping("/policies/{ref}")
    public ServicingViews.ServicingView view(@PathVariable String ref) {
        return servicing.view(ref);
    }

    /** {type, effectiveDate?, …changes}: what the endorsement would do; nothing is changed. */
    @PostMapping("/policies/{ref}/endorsements/preview")
    public ServicingViews.Preview preview(@PathVariable String ref, @RequestBody JsonNode body) {
        return servicing.preview(ref, body);
    }

    /** {type, effectiveDate?, reason, …changes} */
    @PostMapping("/policies/{ref}/endorsements")
    public ServicingViews.EndorsementView request(@PathVariable String ref, @RequestBody JsonNode body) {
        return servicing.request(ref, body);
    }

    @GetMapping("/endorsements")
    public List<ServicingViews.EndorsementView> endorsements(@RequestParam(required = false) String status) {
        return servicing.endorsements(status);
    }

    /** {approve, note} */
    @PostMapping("/endorsements/{ref}/decision")
    public ServicingViews.EndorsementView decide(@PathVariable String ref, @RequestBody JsonNode body) {
        return servicing.decide(ref, body);
    }

    @GetMapping("/reinstatements")
    public List<ServicingViews.PolicyView> reinstatements() {
        return servicing.reinstatements();
    }

    /** {approve, note} */
    @PostMapping("/policies/{ref}/reinstatement")
    public ServicingViews.PolicyView decideReinstatement(@PathVariable String ref, @RequestBody JsonNode body) {
        return servicing.decideReinstatement(ref, body);
    }

    /** {reason DEATH|MATURITY|OTHER, note, effectiveDate?} */
    @PostMapping("/policies/{ref}/termination")
    public ServicingViews.PolicyView terminate(@PathVariable String ref, @RequestBody JsonNode body) {
        return servicing.terminate(ref, body);
    }

    @GetMapping("/policies/{ref}/surrender-quote")
    public ServicingViews.SurrenderQuote surrenderQuote(@PathVariable String ref) {
        return servicing.surrenderQuote(ref);
    }

    /** {reason} */
    @PostMapping("/policies/{ref}/surrender")
    public ServicingViews.BenefitEventView surrender(@PathVariable String ref, @RequestBody JsonNode body) {
        return servicing.surrender(ref, body);
    }

    /** {reason} */
    @PostMapping("/policies/{ref}/paid-up")
    public ServicingViews.ServicingView paidUp(@PathVariable String ref, @RequestBody JsonNode body) {
        return servicing.makePaidUp(ref, body);
    }

    @GetMapping("/benefit-events")
    public List<ServicingViews.BenefitEventView> benefitEvents(@RequestParam(required = false) String status) {
        return servicing.benefitEvents(status);
    }

    @PostMapping("/benefit-events/run")
    public ServicingViews.RunResult runBenefits() {
        return servicing.runBenefits();
    }

    /** {approve, note} */
    @PostMapping("/benefit-events/{ref}/decision")
    public ServicingViews.BenefitEventView decideBenefit(@PathVariable String ref, @RequestBody JsonNode body) {
        return servicing.decideBenefit(ref, body);
    }

    /** {paymentReference} */
    @PostMapping("/benefit-events/{ref}/payment")
    public ServicingViews.BenefitEventView payBenefit(@PathVariable String ref, @RequestBody JsonNode body) {
        return servicing.payBenefit(ref, body);
    }

    @GetMapping("/life-reviews")
    public List<ServicingViews.LifeReviewView> lifeReviews() {
        return servicing.lifeReviews();
    }

    @PostMapping("/life-reviews/run")
    public ServicingViews.RunResult runLifeReviews() {
        return servicing.runLifeReviews();
    }

    /** {outcome CONTINUE|CEASE, note} */
    @PostMapping("/life-reviews/{id}/close")
    public ServicingViews.LifeReviewView closeReview(@PathVariable Long id, @RequestBody JsonNode body) {
        return servicing.closeReview(id, body);
    }

    @GetMapping("/benefit-rules")
    public List<ServicingViews.BenefitRuleView> benefitRules() {
        return servicing.benefitRuleViews();
    }

    @PutMapping("/benefit-rules")
    public ServicingViews.BenefitRuleView saveBenefitRule(@RequestBody JsonNode body) {
        return servicing.saveBenefitRule(body);
    }

    /** {status GRANTED|WITHDRAWN, evidence}: an insured life's consent to share their medical evidence. */
    @PostMapping("/policies/{ref}/lives/{lifeId}/consent")
    public ServicingViews.ServicingView recordConsent(@PathVariable String ref, @PathVariable Long lifeId,
            @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        return servicing.recordConsent(ref, lifeId, body);
    }

        /** Ends cover whose selected term or cessation age has passed (also daily). */
    @PostMapping("/term-ends/run")
    public ServicingViews.RunResult endCoverAtTerm() {
        return servicing.endCoverAtTermNow();
    }

        @GetMapping("/rules")
    public List<ServicingViews.ServicingRuleView> servicingRules() {
        return servicing.servicingRules();
    }

    @PutMapping("/rules/{id}")
    public ServicingViews.ServicingRuleView saveServicingRule(@PathVariable Long id, @RequestBody JsonNode body) {
        return servicing.saveServicingRule(id, body);
    }
}
