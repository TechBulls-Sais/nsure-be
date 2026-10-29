package com.nsure.distribution;

import com.nsure.integration.DomainEvent;
import com.nsure.integration.DomainEventHandler;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Writes the commission ledger from events (SH06). A premium received on a policy assigned to an intermediary
 * accrues commission at the rule in force for its product and intermediary type: the initial rate on the first
 * premium, the renewal rate after. A cancellation claws back every accrual on the policy that is not yet clawed
 * back. Each line carries its source event, so a redelivered event adds nothing (AT-SH06-01).
 */
@Component
public class CommissionEventHandler implements DomainEventHandler {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String ACTOR = "system";

    private final EntityManager em;
    private final Outbox outbox;

    public CommissionEventHandler(EntityManager em, Outbox outbox) {
        this.em = em;
        this.outbox = outbox;
    }

    @Override
    public String name() {
        return "distribution.commission";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(Events.PREMIUM_RECEIVED, Events.POLICY_CANCELLED);
    }

    @Override
    public void handle(DomainEvent event) {
        if (Events.PREMIUM_RECEIVED.equals(event.eventType())) {
            accrue(event);
        } else if (Events.POLICY_CANCELLED.equals(event.eventType())) {
            clawBack(event);
        }
    }

    private void accrue(DomainEvent event) {
        if (exists(event.eventId())) {
            return;
        }
        String policyRef = event.text("policyRef");
        String product = event.text("product");
        LocalDate today = LocalDate.now();
        Optional<PolicyAssignment> assignment = em.createQuery("select a from PolicyAssignment a"
                        + " join fetch a.intermediary where a.policyRef = :ref order by a.effectiveFrom desc, a.id desc",
                        PolicyAssignment.class)
                .setParameter("ref", policyRef)
                .getResultList()
                .stream()
                .filter(a -> a.inForceOn(today))
                .findFirst();
        if (assignment.isEmpty() || product == null) {
            return;
        }
        Intermediary intermediary = assignment.get().getIntermediary();
        Optional<CommissionRule> rule = ruleInForce(em, product, intermediary.getType(), today);
        if (rule.isEmpty()) {
            return;
        }
        BigDecimal premium = event.amount("amount");
        BigDecimal rate = event.flag("firstPremium") ? rule.get().getInitialRatePercent()
                : rule.get().getRenewalRatePercent();
        BigDecimal amount = premium.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        Commission line = Commission.accrual(intermediary, policyRef, product, event.eventId(),
                premium.setScale(2, RoundingMode.HALF_UP), rate, amount);
        em.persist(line);
        em.flush();
        line.numbered();
        publish(Events.COMMISSION_ACCRUED, line, amount, event);
    }

    private void clawBack(DomainEvent event) {
        String policyRef = event.text("policyRef");
        List<Commission> open = em.createQuery("select c from Commission c join fetch c.intermediary"
                        + " where c.policyRef = :ref and c.kind = :accrual"
                        + " and not exists (select r.id from Commission r where r.reverses = c) order by c.id asc",
                        Commission.class)
                .setParameter("ref", policyRef)
                .setParameter("accrual", Commission.ACCRUAL)
                .getResultList();
        for (Commission original : open) {
            Commission line = Commission.clawback(original, event.eventId());
            if (exists(line.getSourceEventId())) {
                continue;
            }
            em.persist(line);
            em.flush();
            line.numbered();
            publish(Events.COMMISSION_REVERSED, line, original.getAmount(), event);
        }
    }

    private void publish(String type, Commission line, BigDecimal amount, DomainEvent cause) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("commissionRef", line.getRef());
        data.put("intermediaryRef", line.getIntermediary().getRef());
        data.put("policyRef", line.getPolicyRef());
        data.put("product", line.getProduct());
        data.put("amount", amount.toPlainString());
        outbox.publish(type, line.getRef(), data, ACTOR, cause.correlationId());
    }

    private boolean exists(String sourceEventId) {
        return !em.createQuery("select c.id from Commission c where c.sourceEventId = :source", Long.class)
                .setParameter("source", sourceEventId)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    static Optional<CommissionRule> ruleInForce(EntityManager em, String product, String type, LocalDate day) {
        return em.createQuery("select r from CommissionRule r where r.product = :product and r.intermediaryType = :type"
                        + " and r.effectiveFrom <= :day order by r.effectiveFrom desc", CommissionRule.class)
                .setParameter("product", product)
                .setParameter("type", type)
                .setParameter("day", day)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }
}
