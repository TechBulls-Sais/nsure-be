package com.nsure.payment;

import java.util.UUID;

/**
 * A rail that settles inline: the mock mobile-money/EFT gateway (until DPO's Botswana mobile-money support is
 * confirmed, blocker B4) and staff-recorded receipts. It confirms exactly what the transaction asked for, read back
 * from the transaction itself, so it runs through the same verify-then-settle path as a hosted checkout (S-24).
 */
public class InlineGateway implements PaymentGateway {

    private final String provider;
    private final PaymentTransactionRepository transactions;

    public InlineGateway(String provider, PaymentTransactionRepository transactions) {
        this.provider = provider;
        this.transactions = transactions;
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public TokenResult createToken(PaymentRequest request) {
        return new TokenResult.Immediate(provider + "-" + UUID.randomUUID());
    }

    @Override
    public Verification verify(String reference, String token) {
        return transactions.findByProviderReference(reference)
                .map(tx -> new Verification(Outcome.PAID, tx.getAmount(), tx.getCurrency(), "000",
                        provider + " confirmed " + tx.getCurrency() + " " + tx.getAmount()))
                .orElse(new Verification(Outcome.NOT_FOUND, null, null, "404", "Unknown reference " + reference));
    }
}
