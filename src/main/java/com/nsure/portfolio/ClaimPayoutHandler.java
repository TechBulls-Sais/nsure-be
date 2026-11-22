package com.nsure.portfolio;

import com.nsure.payment.PayoutInstruction;
import com.nsure.payment.PayoutOutcomeHandler;
import org.springframework.stereotype.Component;

/** Moves a claim when the payout rail answers for its instruction (settled, rejected, returned). */
@Component
public class ClaimPayoutHandler implements PayoutOutcomeHandler {

    private final ClaimWorkflow claims;

    public ClaimPayoutHandler(ClaimWorkflow claims) {
        this.claims = claims;
    }

    @Override
    public String sourceType() {
        return ClaimWorkflow.PAYOUT_SOURCE;
    }

    @Override
    public void settled(PayoutInstruction instruction, String actor) {
        claims.settledByRail(instruction, actor);
    }

    @Override
    public void failed(PayoutInstruction instruction, String reason, String actor) {
        claims.failedByRail(instruction, reason, actor);
    }

    @Override
    public void returned(PayoutInstruction instruction, String reason, String actor) {
        claims.returnedByRail(instruction, reason, actor);
    }
}
