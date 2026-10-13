package com.nsure.mipas.control;

import com.nsure.security.exception.NoAuthorizationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FourEyesControl {

    private static final Logger log = LoggerFactory.getLogger(FourEyesControl.class);

    private final ControlService controls;

    public FourEyesControl(ControlService controls) {
        this.controls = controls;
    }

    /**
     * @return true when four-eyes was actually enforced
     */
    public boolean assertClaimApproval(Long recommendedBy, Long actorId, boolean overrideAuthority, String overrideReason) {
        if (!controls.isEnabled(ControlKey.CLAIM_FOUR_EYES)) {
            log.warn("OPERATIONAL CONTROL BYPASSED: claim.four_eyes actor={}", actorId);
            return false;
        }
        if (recommendedBy == null) {
            throw new NoAuthorizationException(
                    "This claim has no recorded assessment, so a second approver cannot be verified. "
                            + "It must be assessed before it is approved.");
        }
        if (recommendedBy.equals(actorId)) {
            if (overrideAuthority && overrideReason != null && !overrideReason.isBlank()) {
                log.warn("FOUR EYES OVERRIDDEN actor={} reason={}", actorId, overrideReason.trim());
                return false;
            }
            throw new NoAuthorizationException(
                    "You assessed this claim, so you cannot also authorise its payout. "
                            + "A second approver must action it.");
        }
        return true;
    }
}
