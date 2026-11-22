package com.nsure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
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
import java.util.List;
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
 * Cash is never treated as premium just because a notification arrived (NS-SH04-004): the provider is asked
 * through {@link ProviderStatusClient} first, and a receipt it does not confirm goes to suspense too.
 *
 * <p>NS-SH12-004: when the provider does not answer the status query (a timeout), the outcome is <b>unknown</b>: the
 * confirmation is kept as UNKNOWN with no financial effect and is re-queried later (on demand, by the sweep, or when
 * the interface is restored). Nothing is ever re-submitted to the provider, so a payment that may have settled is
 * never taken twice. Confirmations arrive from integration staff or as signed provider callbacks
 * ({@link PaymentCallbacks}).
 */
@Service
public class PaymentNotifications {

    static final String TYPE = "PAYMENT_CONFIRMATION";

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final PolicyWorkflow policies;
    private final Outbox outbox;
    private final ProviderStatusClient providerStatus;

    public PaymentNotifications(EntityManager em, PlatformSecurityContext security, PolicyWorkflow policies,
            Outbox outbox, ProviderStatusClient providerStatus) {
        this.em = em;
        this.security = security;
        this.policies = policies;
        this.outbox = outbox;
        this.providerStatus = providerStatus;
    }

    /** A confirmation recorded by integration staff. */
    @Transactional
    public IntegrationViews.InboundView receive(JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_INTEGRATION");
        return process(body, user.getUsername(), InboundMessage.STAFF);
    }

    /** A provider callback whose signature and replay window {@link PaymentCallbacks} has already verified. */
    @Transactional
    public IntegrationViews.InboundView receiveVerified(JsonNode body) {
        return process(body, "provider:" + required(body, "provider"), InboundMessage.SIGNED_CALLBACK);
    }

    private IntegrationViews.InboundView process(JsonNode body, String actor, String channel) {
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
            // AT-SH12-01: acknowledged again with the first result; no second business effect.
            return IntegrationService.inbound(earlier.get());
        }

        InboundMessage message = new InboundMessage(provider, providerRef, TYPE, hash, policyRef, amount, actor);
        message.arrived(channel, currency, method);
        decide(message, actor);
        em.persist(message);
        return IntegrationService.inbound(message);
    }

    /**
     * NS-SH12-004: asks the provider again about a confirmation whose outcome is unknown. Only the status query is
     * repeated; the payment itself is never re-submitted. Integration staff only.
     */
    @Transactional
    public IntegrationViews.InboundView requery(Long id) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_INTEGRATION");
        InboundMessage message = em.find(InboundMessage.class, id);
        if (message == null) {
            throw new NotFoundException("Inbound message " + id + " not found");
        }
        if (!InboundMessage.UNKNOWN.equals(message.getStatus())) {
            throw new ConflictException("Only a confirmation with an unknown outcome is re-queried; this one is "
                    + message.getStatus() + ".");
        }
        decide(message, user.getUsername());
        return IntegrationService.inbound(message);
    }

    /** Ids of confirmations whose outcome is still unknown, oldest first, for the re-query sweep. */
    @Transactional(readOnly = true)
    public List<Long> unknownIds() {
        return em.createQuery("select m.id from InboundMessage m where m.status = :unknown order by m.id", Long.class)
                .setParameter("unknown", InboundMessage.UNKNOWN).getResultList();
    }

    /** The sweep's re-query of one unknown confirmation, in its own transaction; returns the resulting status. */
    @Transactional
    public String requerySystem(Long id) {
        InboundMessage message = em.find(InboundMessage.class, id);
        if (message == null || !InboundMessage.UNKNOWN.equals(message.getStatus())) {
            return message == null ? null : message.getStatus();
        }
        decide(message, "system");
        return message.getStatus();
    }

    /** Applies, suspends or (when the provider cannot be asked) leaves the confirmation UNKNOWN. */
    private void decide(InboundMessage message, String actor) {
        String provider = message.getProvider();
        String providerRef = message.getProviderRef();
        String policyRef = message.getBusinessRef();
        BigDecimal amount = message.getAmount();
        String currency = message.getCurrency() == null ? "BWP" : message.getCurrency();
        String method = message.getPaymentMethod() == null ? "Bank EFT" : message.getPaymentMethod();
        String problem = problem(policyRef, amount, currency);
        if (problem == null) {
            try {
                problem = unconfirmed(message, provider, providerRef, amount, currency);
            } catch (InterfaceUnavailableException ex) {
                message.outcome(InboundMessage.UNKNOWN, null, ex.getMessage()
                        + ". Nothing is applied or re-sent; the status is asked again until the provider answers.");
                return;
            }
        }
        if (problem == null) {
            // SH04: the provider confirmed it holds this money under this reference.
            Map<String, Object> confirmed = new LinkedHashMap<>();
            confirmed.put("receiptRef", provider + ":" + providerRef);
            confirmed.put("provider", provider);
            confirmed.put("providerRef", providerRef);
            confirmed.put("policyRef", policyRef);
            confirmed.put("amount", amount.toPlainString());
            confirmed.put("currency", currency);
            confirmed.put("method", method);
            confirmed.put("source", InboundMessage.SIGNED_CALLBACK.equals(message.getChannel()) ? "SIGNED_CALLBACK"
                    : "NOTIFICATION");
            outbox.publish(Events.RECEIPT_CONFIRMED, providerRef, confirmed, actor);
            try {
                policies.pay(policyRef, method, "integration:" + provider);
                message.outcome(InboundMessage.APPLIED, policyRef, "Premium collected by " + method
                        + "; confirmed by " + provider
                        + (InboundMessage.SIGNED_CALLBACK.equals(message.getChannel()) ? " (signed callback)" : ""));
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
            outbox.publish(Events.UNMATCHED_RECEIPT, providerRef, data, actor);
        }
    }

    /** Null when the provider itself confirms it holds this amount under this reference; otherwise why not. */
    private String unconfirmed(InboundMessage message, String provider, String providerRef, BigDecimal amount,
            String currency) {
        message.statusQueried();
        Optional<ProviderStatusClient.ProviderStatus> status = providerStatus.status(provider, providerRef);
        if (status.isEmpty()) {
            return provider + " offers no status query: match " + providerRef
                    + " to the bank statement before allocating it";
        }
        ProviderStatusClient.ProviderStatus said = status.get();
        if (!said.received()) {
            return provider + " has no record of receiving " + providerRef;
        }
        if (said.amount() == null || said.amount().compareTo(amount) != 0 || !currency.equals(said.currency())) {
            return provider + " confirmed " + said.currency() + " "
                    + (said.amount() == null ? "?" : said.amount().toPlainString()) + " under " + providerRef
                    + ", not " + currency + " " + amount.toPlainString();
        }
        return null;
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
