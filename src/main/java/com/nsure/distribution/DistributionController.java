package com.nsure.distribution;

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

@RestController
@RequestMapping("/distribution")
public class DistributionController {

    private final DistributionService distribution;

    private final DistributionControls controls;

    public DistributionController(DistributionService distribution, DistributionControls controls) {
        this.distribution = distribution;
        this.controls = controls;
    }

    @PostMapping("/assignments/{id}/approve")
    public DistributionViews.AssignmentView approveTransfer(@PathVariable Long id) {
        return distribution.decideTransfer(id, true);
    }

    @PostMapping("/assignments/{id}/reject")
    public DistributionViews.AssignmentView rejectTransfer(@PathVariable Long id) {
        return distribution.decideTransfer(id, false);
    }

    @PostMapping("/statements/{ref}/approve")
    public DistributionViews.StatementView approveStatement(@PathVariable String ref) {
        return distribution.approveStatement(ref);
    }

    @GetMapping("/exceptions")
    public List<DistributionViews.ExceptionView> exceptions() {
        return controls.exceptions();
    }

    @PostMapping("/exceptions/{ref}/retry")
    public DistributionViews.ExceptionView retryException(@PathVariable String ref) {
        return controls.retry(ref);
    }

    /** {resolution} */
    @PostMapping("/exceptions/{ref}/resolve")
    public DistributionViews.ExceptionView resolveException(@PathVariable String ref, @RequestBody JsonNode body) {
        return controls.resolve(ref, body);
    }

    /** {reason} */
    @PostMapping("/commissions/{ref}/dispute")
    public DistributionViews.CommissionView dispute(@PathVariable String ref, @RequestBody JsonNode body) {
        return controls.dispute(ref, body);
    }

    /** {note} */
    @PostMapping("/commissions/{ref}/resolve-dispute")
    public DistributionViews.CommissionView resolveDispute(@PathVariable String ref, @RequestBody JsonNode body) {
        return controls.resolveDispute(ref, body);
    }

    @GetMapping("/payment-batches")
    public List<DistributionViews.PaymentBatchView> batches() {
        return controls.batches();
    }

    @PostMapping("/payment-batches")
    public DistributionViews.PaymentBatchView prepareBatch() {
        return controls.prepareBatch();
    }

    @PostMapping("/payment-batches/{ref}/release")
    public DistributionViews.PaymentBatchView releaseBatch(@PathVariable String ref) {
        return controls.releaseBatch(ref);
    }

    @GetMapping("/agreements")
    public List<DistributionViews.AgreementView> agreements() {
        return controls.agreements();
    }

    /** {intermediaryRef, products?, channels?, clawbackMonths, bankName?, bankAccount?, bankBranch?, effectiveFrom?, note?} */
    @PostMapping("/agreements")
    public DistributionViews.AgreementView draftAgreement(@RequestBody JsonNode body) {
        return controls.draftAgreement(body);
    }

    @PostMapping("/agreements/{ref}/activate")
    public DistributionViews.AgreementView activateAgreement(@PathVariable String ref) {
        return controls.activateAgreement(ref);
    }

    @GetMapping("/intermediaries")
    public List<DistributionViews.IntermediaryView> intermediaries() {
        return distribution.intermediaries();
    }

    /** {name, type AGENT|BROKER, licenceNumber, appointmentExpiry, parentRef?, email?, mobile?} */
    @PostMapping("/intermediaries")
    public DistributionViews.IntermediaryView appoint(@RequestBody JsonNode body) {
        return distribution.appoint(body);
    }

    /** {status? ACTIVE|SUSPENDED|LAPSED, reason? (required to suspend or lapse), appointmentExpiry?, name?, ...} */
    @PutMapping("/intermediaries/{ref}")
    public DistributionViews.IntermediaryView updateIntermediary(@PathVariable String ref, @RequestBody JsonNode body) {
        return distribution.updateIntermediary(ref, body);
    }

    @GetMapping("/commission-rules")
    public List<DistributionViews.CommissionRuleView> rules() {
        return distribution.rules();
    }

    /** {product, intermediaryType, initialRatePercent, renewalRatePercent, effectiveFrom?} */
    @PostMapping("/commission-rules")
    public DistributionViews.CommissionRuleView createRule(@RequestBody JsonNode body) {
        return distribution.createRule(body);
    }

    @GetMapping("/assignments")
    public List<DistributionViews.AssignmentView> assignments(@RequestParam(required = false) String policyRef,
            @RequestParam(required = false) String intermediaryRef) {
        return distribution.assignments(policyRef, intermediaryRef);
    }

    /** {policyRef, intermediaryRef, effectiveFrom?} */
    @PostMapping("/assignments")
    public DistributionViews.AssignmentView assign(@RequestBody JsonNode body) {
        return distribution.assign(body);
    }

    @GetMapping("/commissions")
    public List<DistributionViews.CommissionView> commissions(@RequestParam(required = false) String intermediaryRef,
            @RequestParam(required = false) String policyRef, @RequestParam(required = false) String status) {
        return distribution.commissions(intermediaryRef, policyRef, status);
    }

    @GetMapping("/statements")
    public List<DistributionViews.StatementView> statements() {
        return distribution.statements();
    }

    /** {intermediaryRef} */
    @PostMapping("/statements")
    public DistributionViews.StatementView issueStatement(@RequestBody JsonNode body) {
        return distribution.issueStatement(body);
    }

    @PostMapping("/statements/{ref}/pay")
    public DistributionViews.StatementView payStatement(@PathVariable String ref) {
        return distribution.payStatement(ref);
    }
}
