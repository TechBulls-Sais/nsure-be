package com.nsure.controls;

import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import org.springframework.stereotype.Service;

/**
 * Four-eyes on claim approval (spec 12 §3.2): the person who assessed a claim may not authorise its payout.
 * Enforced on actor identity, not on role membership, so a single Claims Assessor role still needs two people.
 */
@Service
public class FourEyesControl {

    /** What happened at the moment of approval; recorded on the claim so the bypass is never invisible (C-7). */
    public enum Outcome {
        /** A different person assessed the claim. */
        ENFORCED,
        /** The claim.four_eyes control is switched off. */
        CONTROL_OFF,
        /** The assessor approved their own claim under the break-glass authority, with a written reason (C-9). */
        OVERRIDDEN;

        public boolean applied() {
            return this == ENFORCED;
        }
    }

    private final ControlService controls;

    public FourEyesControl(ControlService controls) {
        this.controls = controls;
    }

    public Outcome assertClaimApproval(Long assessorUserId, AppUser approver, String overrideReason) {
        if (!controls.isEnabled(ControlKey.CLAIM_FOUR_EYES.key())) {
            return Outcome.CONTROL_OFF;
        }
        if (assessorUserId == null) {
            // C-8: nobody assessed it. Under a fail-closed control an unverifiable separation is a denial, not a pass.
            throw new NoAuthorizationException("This claim has no recorded assessment, so a second approver cannot "
                    + "be verified. It must be assessed before it is approved.");
        }
        if (assessorUserId.equals(approver.getId())) {
            if (approver.hasPermissionTo("OVERRIDE_CLAIM_FOUR_EYES")
                    && overrideReason != null && !overrideReason.isBlank()) {
                return Outcome.OVERRIDDEN;
            }
            throw new NoAuthorizationException("You assessed this claim, so you cannot also authorise its payout. "
                    + "A second approver must action it.");
        }
        return Outcome.ENFORCED;
    }
}
