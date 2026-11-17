package com.nsure.payment;

import jakarta.persistence.EntityManager;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Chooses the rail per payment method (spec 13 S-25): Card goes to hosted checkout; Orange Money, MyZaka and Bank EFT
 * stay on the inline mock until DPO's Botswana mobile-money support is confirmed (blocker B4). Staff-recorded receipts
 * use the MANUAL rail. {@code nsure.payments.card-gateway=MOCK} keeps cards inline (tests, demos without a browser).
 *
 * <p>The mock and the hosted-checkout simulator confirm money no provider received, so the prod profile does not use
 * them: a method without a real provider is refused before an attempt exists (nothing is charged, cover stays
 * unpaid), {@code card-gateway} must be {@code NONE} or start-up fails, and an attempt a simulated rail started earlier
 * is never verified as paid — it waits, unconfirmed, until it expires. A demonstration installation that runs the prod
 * profile opts back in with {@code nsure.payments.demo=true} ({@code NSURE_DEMO_PAYMENTS}): everything then works as in
 * development, and start-up logs a warning.
 */
@Component
public class PaymentGateways {

    public static final String MOCK = "MOCK";
    public static final String MANUAL = "MANUAL";

    /** {@code nsure.payments.card-gateway}: the hosted-checkout simulator (development and demos only). */
    public static final String SIMULATOR = "SIMULATOR";
    /** {@code nsure.payments.card-gateway}: no card provider is connected, so card payments are refused. */
    public static final String NONE = "NONE";

    private static final Set<String> CARD_GATEWAYS = Set.of(SIMULATOR, MOCK, NONE);
    private static final Logger log = LoggerFactory.getLogger(PaymentGateways.class);

    private final Map<String, PaymentGateway> byProvider;
    /** {@code null} when no card provider is connected. */
    private final String cardProvider;
    private final boolean simulatorsEnabled;

    public PaymentGateways(PaymentTransactionRepository transactions, EntityManager em, Environment environment,
            @Value("${nsure.payments.demo:false}") boolean demo,
            @Value("${nsure.payments.card-gateway:SIMULATOR}") String cardGateway,
            @Value("${nsure.payments.public-base-url:http://127.0.0.1:5180/api}") String publicBaseUrl,
            @Value("${nsure.payments.token-minutes:30}") int tokenMinutes) {
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        this.simulatorsEnabled = !production || demo;
        String card = cardGateway == null ? "" : cardGateway.trim().toUpperCase(Locale.ROOT);
        if (card.isEmpty()) {
            // Not configured: the simulator wherever simulated rails may run, otherwise no card payments.
            card = simulatorsEnabled ? SIMULATOR : NONE;
        }
        if (!CARD_GATEWAYS.contains(card)) {
            throw new IllegalStateException("nsure.payments.card-gateway must be NONE, SIMULATOR or MOCK, not \""
                    + cardGateway + "\".");
        }
        if (!simulatorsEnabled && !NONE.equals(card)) {
            throw new IllegalStateException("The prod profile cannot send card payments to the " + card
                    + " gateway: it confirms payments no provider received. Set NSURE_CARD_GATEWAY=NONE until a"
                    + " real card provider is connected, or NSURE_DEMO_PAYMENTS=true on a demonstration installation.");
        }
        if (production && demo) {
            log.warn("DEMO PAYMENTS ARE ON under the prod profile (NSURE_DEMO_PAYMENTS=true): Orange Money, MyZaka and"
                    + " Bank EFT settle through the mock, and cards through the {} gateway, without any money being"
                    + " received, and they activate cover. Only for a demonstration installation; never for live"
                    + " business.", card);
        }
        PaymentGateway manual = new InlineGateway(MANUAL, transactions);
        if (simulatorsEnabled) {
            this.byProvider = Map.of(MANUAL, manual, MOCK, new InlineGateway(MOCK, transactions),
                    HostedCheckoutSimulator.PROVIDER, new HostedCheckoutSimulator(em, publicBaseUrl, tokenMinutes));
        } else {
            // Attempts a simulated rail started earlier (before the prod profile, or with demo payments): never paid.
            this.byProvider = Map.of(MANUAL, manual, MOCK, new Unconfirmed(MOCK),
                    HostedCheckoutSimulator.PROVIDER, new Unconfirmed(HostedCheckoutSimulator.PROVIDER));
        }
        this.cardProvider = switch (card) {
            case MOCK -> MOCK;
            case SIMULATOR -> HostedCheckoutSimulator.PROVIDER;
            default -> null;
        };
    }

    /**
     * The rail for a customer payment. A method no provider can take here is refused (a business-rule error for the
     * customer) before any attempt is created.
     */
    public PaymentGateway forMethod(String method) {
        if ("Card".equalsIgnoreCase(method)) {
            if (cardProvider == null) {
                throw notAvailable("Card");
            }
            return byProvider.get(cardProvider);
        }
        if (!simulatorsEnabled) {
            throw notAvailable(method);
        }
        return byProvider.get(MOCK);
    }

    public PaymentGateway manual() {
        return byProvider.get(MANUAL);
    }

    public PaymentGateway byProvider(String provider) {
        PaymentGateway gateway = byProvider.get(provider);
        if (gateway == null) {
            throw new IllegalStateException("No payment gateway for provider " + provider);
        }
        return gateway;
    }

    /** False under the prod profile unless demo payments are on: no mock or simulator may confirm money there. */
    public boolean simulatorsEnabled() {
        return simulatorsEnabled;
    }

    private static IllegalArgumentException notAvailable(String method) {
        return new IllegalArgumentException(method + " payments are not available yet: no payment provider is"
                + " connected for them, so nothing was charged.");
    }

    /**
     * Stands in for a simulated rail under the prod profile. It never reports a payment, so an attempt that rail
     * started stays unconfirmed until it expires; it starts none of its own.
     */
    private record Unconfirmed(String provider) implements PaymentGateway {

        @Override
        public TokenResult createToken(PaymentRequest request) {
            return new TokenResult.Rejected("NO_PROVIDER", provider + " is a simulated rail and is off here.");
        }

        @Override
        public Verification verify(String reference, String token) {
            return new Verification(Outcome.PENDING, null, null, "NO_PROVIDER", provider
                    + " is a simulated rail and cannot confirm a payment here; waiting for a real provider.");
        }
    }
}
