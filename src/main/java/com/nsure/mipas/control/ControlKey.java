package com.nsure.mipas.control;

import java.util.Arrays;
import java.util.List;

public enum ControlKey {
    CLAIM_FOUR_EYES("claim.four_eyes"),
    CLAIM_STP_ENABLED("claim.stp_enabled"),
    CLAIM_STP_CEILING("claim.stp_ceiling"),
    CLAIM_STP_REQUIRES_VERIFIED_PAYOUT("claim.stp_requires_verified_payout"),
    RATE_FOUR_EYES("rate.four_eyes"),
    RATE_NOTICE_DAYS("rate.notice_days"),
    POLICY_PAYMENT_WINDOW_HOURS("policy.payment_window_hours"),
    DECLARATION_OTP_MAX_ATTEMPTS("declaration.otp_max_attempts"),
    DECLARATION_OTP_LOCK_MINUTES("declaration.otp_lock_minutes"),
    PAYMENT_SETTLEMENT_TAMPER_CHECK("payment.settlement_tamper_check");

    private final String key;

    ControlKey(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static List<ControlKey> all() {
        return List.of(values());
    }

    public static ControlKey fromKey(String key) {
        return Arrays.stream(values())
                .filter(item -> item.key.equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown operational control '" + key + "'. Add it to ControlKey first."));
    }
}
