package com.nsure.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A payment rail (spec 13 §7.2). {@link TokenResult.Immediate} keeps an inline-settling rail on the identical code
 * path as a hosted-checkout redirect: the caller settles at once instead of redirecting (S-24). The gateway is
 * chosen per payment method (S-25).
 */
public interface PaymentGateway {

    /** Stored on the transaction: MOCK, MANUAL, HOSTED_SIMULATOR, DPO … */
    String provider();

    TokenResult createToken(PaymentRequest request);

    /** The only source of truth about whether money was taken (S-13). */
    Verification verify(String reference, String token);

    record PaymentRequest(String reference, String policyRef, BigDecimal amount, String currency, String method,
            String description) {
    }

    sealed interface TokenResult {
        record Redirect(String token, String redirectUrl, LocalDateTime expiresAt) implements TokenResult {
        }

        record Immediate(String token) implements TokenResult {
        }

        record Rejected(String code, String message) implements TokenResult {
        }
    }

    enum Outcome { PAID, DECLINED, PENDING, NOT_FOUND }

    record Verification(Outcome outcome, BigDecimal amount, String currency, String resultCode, String resultText) {

        public boolean isPaid() {
            return outcome == Outcome.PAID;
        }

        /** Tamper check (S-14): charged exactly what we asked, in the right currency. */
        public boolean matches(BigDecimal expected, String expectedCurrency) {
            return amount != null && amount.compareTo(expected) == 0 && expectedCurrency.equalsIgnoreCase(currency);
        }
    }
}
