package com.nsure.carecash;

import com.nsure.payment.PayoutInstruction;
import com.nsure.payment.PayoutOutcomeHandler;
import org.springframework.stereotype.Component;

/** Moves a Care Cash event when the payout rail answers for its payment instruction. */
@Component
public class CareCashPayoutHandler implements PayoutOutcomeHandler {

    private final CareCashService careCash;

    public CareCashPayoutHandler(CareCashService careCash) {
        this.careCash = careCash;
    }

    @Override
    public String sourceType() {
        return CareCashService.PAYOUT_SOURCE;
    }

    @Override
    public void settled(PayoutInstruction instruction, String actor) {
        careCash.settledByRail(instruction, actor);
    }

    @Override
    public void failed(PayoutInstruction instruction, String reason, String actor) {
        careCash.failedByRail(instruction, reason, actor);
    }

    @Override
    public void returned(PayoutInstruction instruction, String reason, String actor) {
        careCash.returnedByRail(instruction, reason, actor);
    }
}
