package com.nsure.controls;

import com.nsure.security.exception.NoAuthorizationException;
import org.springframework.stereotype.Service;

@Service
public class FourEyesControl {

    private final ControlService controls;

    public FourEyesControl(ControlService controls) {
        this.controls = controls;
    }

    /**
     * @return true when four-eyes was enforced. False means the control was off and the bypass is visible.
     */
    public boolean assertClaimApproval(Long assessorUserId, Long approverUserId) {
        if (!controls.isEnabled(ControlKey.CLAIM_FOUR_EYES.key())) {
            return false;
        }
        if (assessorUserId == null) {
            throw new IllegalArgumentException("A claim with no assessment cannot be approved.");
        }
        if (assessorUserId.equals(approverUserId)) {
            throw new NoAuthorizationException(
                    "You assessed this claim, so you cannot approve it. A different person must approve it.");
        }
        return true;
    }
}
