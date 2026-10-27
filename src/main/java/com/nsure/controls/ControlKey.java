package com.nsure.controls;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

public enum ControlKey {
    CLAIM_FOUR_EYES("claim.four_eyes", "TOGGLE"),
    CLAIM_STP_ENABLED("claim.stp_enabled", "TOGGLE"),
    CLAIM_STP_CEILING("claim.stp_ceiling", "THRESHOLD"),
    CLAIM_STP_REQUIRES_VERIFIED_PAYOUT("claim.stp_requires_verified_payout", "TOGGLE"),
    RATE_FOUR_EYES("rate.four_eyes", "TOGGLE"),
    RATE_NOTICE_DAYS("rate.notice_days", "DURATION"),
    POLICY_PAYMENT_WINDOW_HOURS("policy.payment_window_hours", "DURATION"),
    DECLARATION_OTP_MAX_ATTEMPTS("declaration.otp_max_attempts", "THRESHOLD"),
    DECLARATION_OTP_LOCK_MINUTES("declaration.otp_lock_minutes", "DURATION"),
    PAYMENT_SETTLEMENT_TAMPER_CHECK("payment.settlement_tamper_check", "TOGGLE");

    private final String key;
    private final String type;

    ControlKey(String key, String type) {
        this.key = key;
        this.type = type;
    }

    public String key() {
        return key;
    }

    public String type() {
        return type;
    }

    public boolean requiresValue() {
        return "THRESHOLD".equals(type) || "DURATION".equals(type);
    }

    public static List<ControlKey> all() {
        return List.of(values());
    }

    public static ControlKey require(String key) {
        return Arrays.stream(values())
                .filter(item -> item.key.equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown operational control '" + key + "'. Add it to ControlKey first."));
    }

    public static boolean needsValue(String type, BigDecimal value) {
        return ("THRESHOLD".equals(type) || "DURATION".equals(type)) && value == null;
    }
}
