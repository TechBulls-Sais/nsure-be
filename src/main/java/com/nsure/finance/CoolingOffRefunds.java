package com.nsure.finance;

import com.nsure.collections.CollectionMandate;
import com.nsure.integration.DomainEvent;
import com.nsure.integration.DomainEventHandler;
import com.nsure.integration.Events;
import com.nsure.portfolio.InsurancePolicy;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Raises the premium refund a cooling-off cancellation owes (NS-SH04-005, TC-23): a policy cancelled within
 * {@code nsure.finance.cooling-off-days} (demo default 30) of its cover start, with no claim on it, refunds every
 * premium collected and not reversed. The refund waits for approval like any other; when the payer has a payment
 * mandate on file it is paid there, otherwise Finance records the destination first. Raised once per policy.
 */
@Component
public class CoolingOffRefunds implements DomainEventHandler {

    private final EntityManager em;
    private final RefundControls refunds;
    private final int coolingOffDays;

    public CoolingOffRefunds(EntityManager em, RefundControls refunds,
            @Value("${nsure.finance.cooling-off-days:30}") int coolingOffDays) {
        this.em = em;
        this.refunds = refunds;
        this.coolingOffDays = coolingOffDays;
    }

    @Override
    public String name() {
        return "finance.cooling-off-refund";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(Events.POLICY_CANCELLED);
    }

    @Override
    public void handle(DomainEvent event) {
        String policyRef = event.text("policyRef");
        String sourceRef = "COOLOFF-" + policyRef;
        if (policyRef == null || !em.createQuery("select r.id from RefundRequest r where r.sourceRef = :ref",
                Long.class).setParameter("ref", sourceRef).setMaxResults(1).getResultList().isEmpty()) {
            return;
        }
        InsurancePolicy policy = em.createQuery("select p from InsurancePolicy p where p.ref = :ref",
                        InsurancePolicy.class)
                .setParameter("ref", policyRef).getResultStream().findFirst().orElse(null);
        if (policy == null || policy.getCoverStartDate() == null) {
            return;
        }
        LocalDate cancelled = event.occurredAt().toLocalDate();
        if (cancelled.isAfter(policy.getCoverStartDate().plusDays(coolingOffDays))) {
            return;
        }
        // An insured event during cooling-off forfeits the refund.
        if (em.createQuery("select count(c) from InsuranceClaim c where c.policyRef = :ref", Long.class)
                .setParameter("ref", policyRef).getSingleResult() > 0) {
            return;
        }
        BigDecimal paid = em.createQuery("select sum(p.amount) from PremiumPayment p where p.policyRef = :ref"
                        + " and p.status = 'SUCCESSFUL'", BigDecimal.class)
                .setParameter("ref", policyRef).getSingleResult();
        if (paid == null || paid.signum() <= 0) {
            return;
        }
        CollectionMandate mandate = em.createQuery("select m from CollectionMandate m where m.activePolicyRef = :ref",
                        CollectionMandate.class)
                .setParameter("ref", policyRef).getResultStream().findFirst().orElse(null);
        String payee = policy.getPayerName() != null && !policy.getPayerName().isBlank() ? policy.getPayerName()
                : policy.getCustomerName();
        String method = mandate == null ? "Bank Account"
                : CollectionMandate.MOBILE_WALLET.equals(mandate.getChannel()) ? "Mobile Wallet" : "Bank Account";
        refunds.raise("PREMIUM", sourceRef, policyRef, payee.length() <= 150 ? payee : payee.substring(0, 150), method,
                mandate == null ? RefundRequest.TO_CONFIRM : mandate.getAccountNumber(), paid.setScale(2),
                "Cooling-off cancellation within " + coolingOffDays + " days of cover start ("
                        + policy.getCoverStartDate() + "): premiums collected are refundable", "system");
    }
}
