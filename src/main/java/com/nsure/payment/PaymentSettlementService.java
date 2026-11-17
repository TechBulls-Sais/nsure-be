package com.nsure.payment;

import com.nsure.common.exception.NotFoundException;
import com.nsure.controls.ControlKey;
import com.nsure.controls.ControlService;
import com.nsure.controls.StaffAlertService;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.portfolio.InsurancePolicy;
import com.nsure.portfolio.PolicyWorkflow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asynchronous premium settlement (spec 13). {@link #initiate} prices and starts a payment; {@link #settle} is the
 * single settlement path, called by the browser return, the provider callback and the 15-minute sweep — safe to call
 * any number of times, concurrently, from any trigger, in any order:
 *
 * <ol>
 *   <li>lock the transaction row (S-11);</li>
 *   <li>a terminal transaction is already answered (S-12);</li>
 *   <li>verify with the provider — the only source of truth, never a callback body or query string (S-13);</li>
 *   <li>tamper check: amount and currency, before activation (S-14);</li>
 *   <li>write the premium payment and activate the policy through the policy workflow; side effects (schedule PDF,
 *       messages, subledger) follow from outbox events after commit and can never fail the payment (S-15).</li>
 * </ol>
 */
@Service
public class PaymentSettlementService {

    public static final String RETURN = "RETURN";
    public static final String CALLBACK = "CALLBACK";
    public static final String SWEEP = "SWEEP";
    public static final String IMMEDIATE = "IMMEDIATE";

    private final PaymentTransactionRepository transactions;
    private final PaymentGateways gateways;
    private final PolicyWorkflow policies;
    private final ControlService controls;
    private final StaffAlertService alerts;
    private final EntityManager em;
    private final Outbox outbox;

    public PaymentSettlementService(PaymentTransactionRepository transactions, PaymentGateways gateways,
            PolicyWorkflow policies, ControlService controls, StaffAlertService alerts,
            EntityManager em, Outbox outbox) {
        this.outbox = outbox;
        this.transactions = transactions;
        this.gateways = gateways;
        this.policies = policies;
        this.controls = controls;
        this.alerts = alerts;
        this.em = em;
    }

    /**
     * Starts (or resumes) the payment of a policy's next premium. Re-prices server-side (BR-9), reuses an open attempt
     * for the same period so a double-clicked Pay never charges twice (S-8), and either settles inline or returns the
     * hosted-checkout redirect.
     */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public PaymentInitiation initiate(String policyRef, String method, String actor) {
        return initiate(policyRef, method, actor, false);
    }

    /** {@code manual}: a premium staff collected offline and record here (MANUAL rail, settles inline). */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public PaymentInitiation initiate(String policyRef, String method, String actor, boolean manual) {
        InsurancePolicy policy = policies.policy(policyRef);
        em.lock(policy, LockModeType.PESSIMISTIC_WRITE);
        PolicyWorkflow.Period period = policies.assertPayable(policy);
        String paymentMethod = PolicyWorkflow.PAYMENT_METHODS.stream()
                .filter(m -> m.equalsIgnoreCase(method == null ? "" : method.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Choose a payment method: " + String.join(", ", PolicyWorkflow.PAYMENT_METHODS) + "."));
        BigDecimal amount = agreedPremium(policy);
        // Chosen first: a method no provider can take here is refused before an open attempt is resumed.
        PaymentGateway gateway = manual ? gateways.manual() : gateways.forMethod(paymentMethod);

        List<PaymentTransaction> attempts = transactions.findByPolicyRefAndPeriodStartAndPurposeOrderByIdDesc(
                policy.getRef(), period.start(), period.purpose());
        for (PaymentTransaction open : attempts) {
            if (!open.isTerminal() && open.getExpiresAt().isAfter(LocalDateTime.now())
                    && open.getPaymentMethod().equals(paymentMethod) && open.getAmount().compareTo(amount) == 0) {
                return initiation(open);
            }
        }
        String reference = "NSP-" + policy.getRef() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        PaymentTransaction tx = new PaymentTransaction(policy.getRef(), policy.getCustomerRef(), gateway.provider(),
                paymentMethod, reference, idempotencyKey(policy.getRef(), period, attempts.size() + 1), amount, "BWP",
                period.purpose(), period.start(), period.end(), LocalDateTime.now().plusHours(1), actor);
        transactions.save(tx);

        PaymentGateway.PaymentRequest request = new PaymentGateway.PaymentRequest(reference, policy.getRef(), amount,
                "BWP", paymentMethod, policy.getProduct() + " premium " + period.start() + " to " + period.end());
        log(tx, gateway.provider(), "createToken.request", Map.of("reference", reference, "amount", amount.toPlainString(),
                "currency", "BWP", "method", paymentMethod));
        PaymentGateway.TokenResult result = gateway.createToken(request);
        log(tx, gateway.provider(), "createToken.response", result);
        switch (result) {
            case PaymentGateway.TokenResult.Redirect redirect -> {
                tx.redirected(redirect.token(), redirect.redirectUrl(), redirect.expiresAt());
                return initiation(tx);
            }
            case PaymentGateway.TokenResult.Immediate immediate -> {
                tx.inline(immediate.token());
                transactions.flush();
                settle(immediate.token(), IMMEDIATE);
                return initiation(tx);
            }
            case PaymentGateway.TokenResult.Rejected rejected -> {
                tx.fail("The payment provider refused the payment: " + rejected.message());
                return initiation(tx);
            }
        }
    }

    /** The single, idempotent settlement path (spec 13 §5). */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public PaymentTransaction settle(String providerToken, String via) {
        PaymentTransaction tx = transactions.findByProviderTokenForUpdate(providerToken)
                .orElseThrow(() -> new NotFoundException("Unknown payment transaction"));
        if (tx.isTerminal()) {
            return tx;
        }
        tx.markPendingVerification();
        PaymentGateway gateway = gateways.byProvider(tx.getProvider());
        PaymentGateway.Verification verification = gateway.verify(tx.getProviderReference(), providerToken);
        log(tx, tx.getProvider(), "verify (" + via + ")", verification);
        tx.verified(verification.resultCode(), verification.resultText());

        if (controls.isEnabled(ControlKey.PAYMENT_SETTLEMENT_TAMPER_CHECK.key()) && verification.isPaid()
                && !verification.matches(tx.getAmount(), tx.getCurrency())) {
            tx.fail("Amount or currency mismatch: expected " + tx.getCurrency() + " " + tx.getAmount()
                    + ", provider reported " + verification.currency() + " " + verification.amount());
            alerts.raise("PAYMENT_TAMPER", "READ_PAYMENT", "Payment failed the tamper check",
                    "Transaction " + tx.getId() + " for " + tx.getPolicyRef() + ": " + tx.getFailureReason()
                            + ". The policy was not activated; review and refund if money was taken.",
                    "tamper:" + tx.getId(), "system");
            return tx;
        }
        switch (verification.outcome()) {
            case PAID -> {
                // SH04: the provider confirmed the money arrived; applying it to the policy is a separate step.
                receiptConfirmed(tx, via);
                applySuccess(tx, via);
            }
            case DECLINED -> tx.fail(verification.resultText() == null ? "Declined" : verification.resultText());
            case NOT_FOUND -> tx.expire("The provider does not recognise this transaction");
            case PENDING -> {
                if (tx.getExpiresAt().isBefore(LocalDateTime.now())) {
                    tx.expire("The payment session expired before the payment was completed");
                }
            }
        }
        return tx;
    }

    /**
     * The premium to charge: the one agreed when the offer was made (first premium, valid for the payment window) or
     * when cover was issued (renewals). It always comes from the server, never from the client (spec 01 BR-9), and it
     * is never silently re-priced to a later rate version or attained age (Nsure Life NS-IL01-004, NS-SH02-004,
     * AT-SH02-02): a new price needs a new offer the customer accepts.
     */
    private static BigDecimal agreedPremium(InsurancePolicy policy) {
        BigDecimal agreed = policy.getPremium();
        if (agreed == null || agreed.signum() <= 0) {
            throw new IllegalArgumentException("Policy " + policy.getRef() + " has no agreed premium to collect.");
        }
        return agreed.setScale(2, RoundingMode.HALF_UP);
    }

    private void receiptConfirmed(PaymentTransaction tx, String via) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("receiptRef", "PAYTX-" + tx.getId());
        data.put("provider", tx.getProvider());
        data.put("providerRef", tx.getProviderReference() == null ? "" : tx.getProviderReference());
        data.put("policyRef", tx.getPolicyRef());
        data.put("amount", tx.getAmount().toPlainString());
        data.put("currency", tx.getCurrency());
        data.put("method", tx.getPaymentMethod());
        data.put("source", "CHECKOUT");
        data.put("via", via);
        outbox.publish(Events.RECEIPT_CONFIRMED, "PAYTX-" + tx.getId(), data, tx.getCreatedBy());
    }

    private void applySuccess(PaymentTransaction tx, String via) {
        try {
            InsurancePolicy policy = policies.pay(tx.getPolicyRef(), tx.getPaymentMethod(), tx.getCreatedBy(),
                    tx.getId());
            Long paymentId = em.createQuery("select p.id from PremiumPayment p where p.transactionId = :tx", Long.class)
                    .setParameter("tx", tx.getId())
                    .getResultStream().findFirst().orElse(null);
            tx.settle(paymentId, via);
            log(tx, tx.getProvider(), "settled", Map.of("policy", policy.getRef(), "status", policy.getStatus(),
                    "via", via));
        } catch (IllegalArgumentException | com.nsure.common.exception.ConflictException ex) {
            // Money was taken but the policy could not take it (expired meanwhile, period already paid). Never lose
            // it silently: fail the transaction with the reason and tell the people who reconcile payments.
            tx.fail("Paid, but not applied: " + ex.getMessage());
            alerts.raise("PAYMENT_NOT_APPLIED", "READ_PAYMENT", "A paid transaction could not be applied",
                    "Transaction " + tx.getId() + " (" + tx.getCurrency() + " " + tx.getAmount() + ") on "
                            + tx.getPolicyRef() + " was paid at the provider but not applied: " + ex.getMessage()
                            + ". Refund or re-apply it.", "not-applied:" + tx.getId(), "system");
        }
    }

    @Transactional(readOnly = true)
    public PaymentTransaction transaction(Long id) {
        return transactions.findById(id).orElseThrow(() -> new NotFoundException("Payment transaction not found"));
    }

    public PaymentInitiation initiation(PaymentTransaction tx) {
        boolean redirect = PaymentTransaction.REDIRECTED.equals(tx.getStatus()) && tx.getRedirectUrl() != null;
        return new PaymentInitiation(tx.getId(), redirect ? "REDIRECT" : "IMMEDIATE",
                redirect ? tx.getRedirectUrl() : null, tx.getStatus(), tx.isTerminal(), tx.getPolicyRef(),
                tx.getAmount(), tx.getExpiresAt(), tx.getFailureReason());
    }

    private void log(PaymentTransaction tx, String provider, String operation, Object payload) {
        em.persist(new PaymentGatewayLog(tx.getId(), provider, operation, String.valueOf(payload)));
    }

    /** S-8: one key per policy, period, purpose and attempt; a retry after a failure is a new attempt. */
    private static String idempotencyKey(String policyRef, PolicyWorkflow.Period period, int attempt) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (policyRef + ":" + period.start() + ":" + period.purpose() + ":" + attempt)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Spec 13 §8.1 PaymentInitiation. */
    public record PaymentInitiation(Long transactionId, String mode, String redirectUrl, String status,
            boolean terminal, String policyRef, BigDecimal amount, LocalDateTime expiresAt, String failureReason) {
    }
}
