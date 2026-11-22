package com.nsure.collections;

import com.nsure.portfolio.InsurancePolicy;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Matching rules for an inbound receipt (NS-SH04-003). The reference may be an instalment's unique reference
 * (BIL-…) or the policy number; an unknown reference is matched by the payer's account against an active payment
 * mandate. A receipt pays whole premiums oldest-first (each collection settles the policy's earliest unpaid period),
 * up to {@code nsure.collections.max-periods-per-receipt}; what is left over stays in suspense as an unapplied
 * balance. Demo defaults for the insurer's allocation policy (D07).
 */
@Component
public class ReceiptMatcher {

    /** {@code policyRef} the receipt pays; {@code how} says how it was found. */
    public record Resolved(String policyRef, String how) {
    }

    private final EntityManager em;
    private final int maxPeriods;

    public ReceiptMatcher(EntityManager em, @Value("${nsure.collections.max-periods-per-receipt:12}") int maxPeriods) {
        this.em = em;
        this.maxPeriods = maxPeriods;
    }

    /** The policy a receipt quoting {@code reference} (and paid from {@code payerAccount}, if known) is for. */
    public Resolved resolve(String reference, String payerAccount) {
        String ref = reference == null ? "" : reference.trim();
        if (ref.toUpperCase().startsWith("BIL-")) {
            String policy = em.createQuery("select i.policyRef from Instalment i where i.ref = :ref", String.class)
                    .setParameter("ref", ref.toUpperCase()).getResultStream().findFirst().orElse(null);
            if (policy != null) {
                return new Resolved(policy, "instalment " + ref.toUpperCase());
            }
        }
        boolean known = !em.createQuery("select p.id from InsurancePolicy p where p.ref = :ref", Long.class)
                .setParameter("ref", ref).setMaxResults(1).getResultList().isEmpty();
        if (!known && payerAccount != null && !payerAccount.isBlank()) {
            String account = payerAccount.replaceAll("\\s", "");
            String policy = em.createQuery("select m.policyRef from CollectionMandate m where m.activePolicyRef is not null"
                            + " and m.accountNumber = :account", String.class)
                    .setParameter("account", account).setMaxResults(2).getResultList().stream()
                    .reduce((a, b) -> null).orElse(null);
            if (policy != null) {
                return new Resolved(policy, "payer account on mandate");
            }
        }
        return new Resolved(ref, known ? "policy number" : "unmatched");
    }

    /** Whole premiums {@code amount} pays on the policy (0 when it does not cover one premium). */
    public int periods(InsurancePolicy policy, BigDecimal amount) {
        BigDecimal premium = policy.getPremium();
        if (premium == null || premium.signum() <= 0 || amount == null || amount.compareTo(premium) < 0) {
            return 0;
        }
        return Math.min(maxPeriods, amount.divide(premium.setScale(2, RoundingMode.HALF_UP), 0, RoundingMode.DOWN)
                .intValue());
    }
}
