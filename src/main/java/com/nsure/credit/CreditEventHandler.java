package com.nsure.credit;

import com.nsure.integration.DomainEvent;
import com.nsure.integration.DomainEventHandler;
import com.nsure.integration.Events;
import com.nsure.portfolio.InsurancePolicy;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A single-premium credit policy is paid once for the whole loan term, so once it is issued its next premium is not
 * due until the term ends. Without this the monthly arrears process would treat it as unpaid after a month.
 */
@Component
public class CreditEventHandler implements DomainEventHandler {

    private final EntityManager em;
    private final CreditService credit;

    public CreditEventHandler(EntityManager em, CreditService credit) {
        this.em = em;
        this.credit = credit;
    }

    @Override
    public String name() {
        return "credit.single-premium";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(Events.POLICY_ISSUED);
    }

    @Override
    @Transactional
    public void handle(DomainEvent event) {
        CreditRecords.Loan loan = credit.loanForPolicy(event.aggregateRef());
        if (loan == null || !"SINGLE".equals(loan.premiumBasis)) {
            return;
        }
        InsurancePolicy policy = em.createQuery("select p from InsurancePolicy p where p.ref = :ref",
                InsurancePolicy.class).setParameter("ref", loan.policyRef).getResultStream().findFirst().orElse(null);
        if (policy == null || policy.getCoverStartDate() == null) {
            return;
        }
        // Idempotent: the same dates are set however often the event is delivered.
        LocalDate end = policy.getCoverStartDate().plusMonths(loan.termMonths);
        policy.setNextPremiumDue(end);
        policy.setGracePeriodEnd(end.plusDays(30));
    }
}
