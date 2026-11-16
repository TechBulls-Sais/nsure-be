package com.nsure.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money in messages and history: whole pula read "50000", an amount with thebe reads "4500.50" (NS-DAT-003). */
public final class Money {

    private Money() {
    }

    public static String text(BigDecimal value) {
        if (value == null) {
            return "?";
        }
        BigDecimal cents = value.setScale(2, RoundingMode.HALF_UP);
        return cents.remainder(BigDecimal.ONE).signum() == 0
                ? cents.setScale(0, RoundingMode.UNNECESSARY).toPlainString()
                : cents.toPlainString();
    }

    /** The amount as stored: two decimal places. */
    public static BigDecimal of(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal of(long whole) {
        return BigDecimal.valueOf(whole).setScale(2, RoundingMode.UNNECESSARY);
    }
}
