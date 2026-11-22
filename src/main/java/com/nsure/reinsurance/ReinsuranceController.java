package com.nsure.reinsurance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reinsurance")
public class ReinsuranceController {

    private final ReinsuranceService reinsurance;

    public ReinsuranceController(ReinsuranceService reinsurance) {
        this.reinsurance = reinsurance;
    }

    @GetMapping("/treaties")
    public List<ReinsuranceViews.TreatyView> treaties() {
        return reinsurance.treaties();
    }

    /** {name, reinsurer, treatyType, products, cededPercent?, retention?, effectiveFrom, effectiveTo} */
    @PostMapping("/treaties")
    public ReinsuranceViews.TreatyView draft(@RequestBody JsonNode body) {
        return reinsurance.draft(body);
    }

    /** {reason, effectiveFrom, ...terms}: the next version of an approved treaty, approved by someone else. */
    @PostMapping("/treaties/{ref}/amend")
    public ReinsuranceViews.TreatyView amend(@PathVariable String ref, @RequestBody JsonNode body) {
        return reinsurance.amend(ref, body);
    }

    @GetMapping("/reconciliation")
    public ReinsuranceViews.ReconciliationView reconciliation(@RequestParam(required = false) String period) {
        return reinsurance.reconciliation(period);
    }

    @GetMapping("/bordereaux/{ref}/lines")
    public List<ReinsuranceViews.BordereauLine> bordereauLines(@PathVariable String ref) {
        return reinsurance.bordereauLines(ref);
    }

    /** {reference} */
    @PostMapping("/bordereaux/{ref}/acknowledge")
    public ReinsuranceViews.BordereauView acknowledgeBordereau(@PathVariable String ref, @RequestBody JsonNode body) {
        return reinsurance.acknowledgeBordereau(ref, body);
    }

    /** {note} */
    @PostMapping("/bordereaux/{ref}/correction")
    public ReinsuranceViews.BordereauView requestCorrection(@PathVariable String ref, @RequestBody JsonNode body) {
        return reinsurance.requestCorrection(ref, body);
    }

    @PostMapping("/bordereaux/{ref}/correct")
    public ReinsuranceViews.BordereauView correctBordereau(@PathVariable String ref) {
        return reinsurance.correctBordereau(ref);
    }

    /** {reference, amount} */
    @PostMapping("/bordereaux/{ref}/settle")
    public ReinsuranceViews.BordereauView settleBordereau(@PathVariable String ref, @RequestBody JsonNode body) {
        return reinsurance.settleBordereau(ref, body);
    }

    @PostMapping("/treaties/{ref}/approve")
    public ReinsuranceViews.TreatyView approve(@PathVariable String ref) {
        return reinsurance.approve(ref);
    }

    @GetMapping("/cessions")
    public List<ReinsuranceViews.CessionView> cessions(@RequestParam(required = false) String treatyRef,
            @RequestParam(required = false) String policyRef) {
        return reinsurance.cessions(treatyRef, policyRef);
    }

    @GetMapping("/recoveries")
    public List<ReinsuranceViews.RecoveryView> recoveries() {
        return reinsurance.recoveries();
    }

    /** {amount, reference, note?}: the reinsurer paid the recovery. */
    @PostMapping("/recoveries/{ref}/receipt")
    public ReinsuranceViews.RecoveryView receiveRecovery(@PathVariable String ref, @RequestBody JsonNode body) {
        return reinsurance.receiveRecovery(ref, body);
    }

    @GetMapping("/exposures")
    public List<ReinsuranceViews.ExposureView> exposures(@RequestParam(required = false) String policyRef,
            @RequestParam(required = false) String status) {
        return reinsurance.exposures(policyRef, status);
    }

    @GetMapping("/facultative")
    public List<ReinsuranceViews.FacultativeView> facultative() {
        return reinsurance.facultative();
    }

    /** {reinsurer, offeredSum} */
    @PostMapping("/facultative/{ref}/offer")
    public ReinsuranceViews.FacultativeView offer(@PathVariable String ref, @RequestBody JsonNode body) {
        return reinsurance.offer(ref, body);
    }

    /** {outcome ACCEPTED|DECLINED, acceptedSum?, acceptanceRef?, note} */
    @PostMapping("/facultative/{ref}/decision")
    public ReinsuranceViews.FacultativeView decide(@PathVariable String ref, @RequestBody JsonNode body) {
        return reinsurance.decide(ref, body);
    }

    /** {reason} */
    @PostMapping("/recoveries/{ref}/dispute")
    public ReinsuranceViews.RecoveryView dispute(@PathVariable String ref, @RequestBody JsonNode body) {
        return reinsurance.dispute(ref, body);
    }

    /** {note, agreedAmount?} */
    @PostMapping("/recoveries/{ref}/resolve-dispute")
    public ReinsuranceViews.RecoveryView resolveDispute(@PathVariable String ref, @RequestBody JsonNode body) {
        return reinsurance.resolveDispute(ref, body);
    }

    /** {amount?, reason} */
    @PostMapping("/recoveries/{ref}/write-off")
    public ReinsuranceViews.RecoveryView requestWriteOff(@PathVariable String ref, @RequestBody JsonNode body) {
        return reinsurance.requestWriteOff(ref, body);
    }

    @PostMapping("/recoveries/{ref}/write-off/approve")
    public ReinsuranceViews.RecoveryView approveWriteOff(@PathVariable String ref) {
        return reinsurance.decideWriteOff(ref, true);
    }

    @PostMapping("/recoveries/{ref}/write-off/reject")
    public ReinsuranceViews.RecoveryView rejectWriteOff(@PathVariable String ref) {
        return reinsurance.decideWriteOff(ref, false);
    }

    @GetMapping("/bordereaux")
    public List<ReinsuranceViews.BordereauView> bordereaux() {
        return reinsurance.bordereaux();
    }

    /** {treatyRef, period yyyy-MM} */
    @PostMapping("/bordereaux")
    public ReinsuranceViews.BordereauView bordereau(@RequestBody JsonNode body) {
        return reinsurance.bordereau(body);
    }
}
