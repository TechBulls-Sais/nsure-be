package com.nsure.payment;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A redirect-based rail shaped like DPO hosted checkout (spec 07, 13 §1.2): a token, a hosted page on the
 * "provider's" domain where the customer pays or declines, and a server-to-server verification that is the only
 * verdict. It exercises the whole asynchronous design — return, callback, sweep, tamper check — before DPO credentials
 * and a public hostname exist. A real {@code DpoGateway} implements the same interface.
 */
public class HostedCheckoutSimulator implements PaymentGateway {

    public static final String PROVIDER = "HOSTED_SIMULATOR";

    private final EntityManager em;
    private final String publicBaseUrl;
    private final int tokenMinutes;

    public HostedCheckoutSimulator(EntityManager em, String publicBaseUrl, int tokenMinutes) {
        this.em = em;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        this.tokenMinutes = tokenMinutes;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public TokenResult createToken(PaymentRequest request) {
        String token = UUID.randomUUID().toString();
        em.persist(new SimulatedCheckout(token, request.reference(), request.amount(), request.currency(),
                request.description()));
        return new TokenResult.Redirect(token, publicBaseUrl + "/payment/simulator/" + token,
                LocalDateTime.now().plusMinutes(tokenMinutes));
    }

    @Override
    public Verification verify(String reference, String token) {
        SimulatedCheckout checkout = em.find(SimulatedCheckout.class, token);
        if (checkout == null || !checkout.getReference().equals(reference)) {
            return new Verification(Outcome.NOT_FOUND, null, null, "904", "Transaction not found");
        }
        return switch (checkout.getOutcome()) {
            case SimulatedCheckout.PAID -> new Verification(Outcome.PAID, checkout.getChargedAmount(),
                    checkout.getCurrency(), "000", "Transaction paid");
            case SimulatedCheckout.DECLINED -> new Verification(Outcome.DECLINED, null, checkout.getCurrency(), "901",
                    "Transaction declined by the card issuer");
            default -> new Verification(Outcome.PENDING, null, checkout.getCurrency(), "900", "Transaction not paid yet");
        };
    }
}
