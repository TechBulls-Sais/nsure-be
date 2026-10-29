package com.nsure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.ConflictException;
import com.nsure.portfolio.InsurancePolicy;
import com.nsure.portfolio.PolicyWorkflow;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment confirmations from a bank or wallet provider (blueprint "Example payment confirmation"): the provider
 * reference is checked first, so a repeated confirmation returns the existing result. A confirmation that matches a
 * payable policy and its premium is collected through the policy workflow; anything else — unknown policy, wrong
 * amount or currency, a policy that can no longer be paid — goes to suspense and stays visible for reconciliation.
 * Cash is never treated as premium just because a notification arrived (NS-SH04-004).
 */
@Service
public class PaymentNotifications {

    static final String TYPE = "PAYMENT_CONFIRMATION";

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final PolicyWorkflow policies;
    private final Outbox outbox;

    public PaymentNotifications(EntityManager em, PlatformSecurityContext security, PolicyWorkflow policies,
            Outbox outbox) {
        this.em = em;
        this.security = security;
        this.policies = policies;
        this.outbox = outbox;
    }

    @Transactional
    public IntegrationViews.InboundView receive(JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_INTEGRATION");
        String provider = required(body, "provider");
        String providerRef = required(body, "providerReference");
        String policyRef = required(body, "policyRef");
        BigDecimal amount;
        try {
            amount = new BigDecimal(required(body, "amount")).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("amount must be a number.");
        }
        String currency = text(body, "currency", "BWP").toUpperCase(Locale.ROOT);
        String method = text(body, "method", "Bank EFT");
        String hash = sha256(String.join("|", provider, providerRef, policyRef, amount.toPlainString(), currency,
                method));

        Optional<InboundMessage> earlier = em.createQuery(
                        "select m from InboundMessage m where m.provider = :provider and m.providerRef = :ref",
                        InboundMessage.class)
                .setParameter("provider", provider)
                .setParameter("ref", providerRef)
                .getResultStream()
                .findFirst();
        if (earlier.isPresent()) {
            if (!earlier.get().getPayloadHash().equals(hash)) {
                throw new ConflictException("Provider reference " + providerRef
                        + " was already received with different details.");
            }
            return IntegrationService.inbound(earlier.get());
        }

        InboundMessage message = new InboundMessage(provider, providerRef, TYPE, hash, policyRef, amount,
                user.getUsername());
        String problem = problem(policyRef, amount, currency);
        if (problem == null) {
            try {
                policies.pay(policyRef, method, "integration:" + provider);
                message.outcome(InboundMessage.APPLIED, policyRef, "Premium collected by " + method);
            } catch (IllegalArgumentException ex) {
                problem = ex.getMessage();
            }
        }
        if (problem != null) {
            message.outcome(InboundMessage.SUSPENSE, null, problem);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("provider", provider);
            data.put("providerRef", providerRef);
            data.put("policyRef", policyRef);
            data.put("amount", amount.toPlainString());
            data.put("reason", problem.length() <= 300 ? problem : problem.substring(0, 300));
            outbox.publish(Events.UNMATCHED_RECEIPT, providerRef, data, user.getUsername());
        }
        em.persist(message);
        return IntegrationService.inbound(message);
    }

    private String problem(String policyRef, BigDecimal amount, String currency) {
        if (!"BWP".equals(currency)) {
            return "Currency " + currency + " is not the policy currency BWP";
        }
        InsurancePolicy policy = em.createQuery("select p from InsurancePolicy p where p.ref = :ref",
                        InsurancePolicy.class)
                .setParameter("ref", policyRef)
                .getResultStream()
                .findFirst()
                .orElse(null);
        if (policy == null) {
            return "No policy " + policyRef;
        }
        if (policy.getPremium() == null || policy.getPremium().compareTo(amount) != 0) {
            return "Amount BWP " + amount.toPlainString() + " does not match the premium BWP "
                    + (policy.getPremium() == null ? "?" : policy.getPremium().toPlainString());
        }
        return null;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String required(JsonNode body, String field) {
        String value = text(body, field, null);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value;
    }

    private static String text(JsonNode body, String field, String fallback) {
        if (body == null || !body.hasNonNull(field) || body.get(field).asText().isBlank()) {
            return fallback;
        }
        return body.get(field).asText().trim();
    }
}
