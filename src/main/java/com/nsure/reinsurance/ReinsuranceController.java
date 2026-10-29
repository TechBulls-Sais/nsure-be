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
