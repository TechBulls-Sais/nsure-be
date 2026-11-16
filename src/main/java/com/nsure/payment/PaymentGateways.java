package com.nsure.payment;

import jakarta.persistence.EntityManager;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Chooses the rail per payment method (spec 13 S-25): Card goes to hosted checkout; Orange Money, MyZaka and Bank EFT
 * stay on the inline mock until DPO's Botswana mobile-money support is confirmed (blocker B4). Staff-recorded receipts
 * use the MANUAL rail. {@code nsure.payments.card-gateway=MOCK} keeps cards inline (tests, demos without a browser).
 */
@Component
public class PaymentGateways {

    public static final String MOCK = "MOCK";
    public static final String MANUAL = "MANUAL";

    private final Map<String, PaymentGateway> byProvider;
    private final String cardProvider;

    public PaymentGateways(PaymentTransactionRepository transactions, EntityManager em,
            @Value("${nsure.payments.card-gateway:SIMULATOR}") String cardGateway,
            @Value("${nsure.payments.public-base-url:http://127.0.0.1:5180/api}") String publicBaseUrl,
            @Value("${nsure.payments.token-minutes:30}") int tokenMinutes) {
        PaymentGateway mock = new InlineGateway(MOCK, transactions);
        PaymentGateway manual = new InlineGateway(MANUAL, transactions);
        PaymentGateway hosted = new HostedCheckoutSimulator(em, publicBaseUrl, tokenMinutes);
        this.byProvider = Map.of(MOCK, mock, MANUAL, manual, HostedCheckoutSimulator.PROVIDER, hosted);
        this.cardProvider = "MOCK".equalsIgnoreCase(cardGateway) ? MOCK : HostedCheckoutSimulator.PROVIDER;
    }

    public PaymentGateway forMethod(String method) {
        return byProvider.get("Card".equalsIgnoreCase(method) ? cardProvider : MOCK);
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
}
