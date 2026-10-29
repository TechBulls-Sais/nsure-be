package com.nsure.reinsurance;

import com.nsure.integration.DomainEvent;
import com.nsure.integration.DomainEventHandler;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.portfolio.InsurancePolicy;
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
 * Cedes risk from events (SH07). A premium received on a product covered by an approved treaty in force today is
 * ceded at the treaty's share: the quota-share percentage, or for surplus the part of the sum assured above the
 * retention. No approved treaty, no cession (AT-SH07-01). A paid claim on a ceded policy recognises a recovery due
 * from the reinsurer, separate from the customer's payout (AT-SH07-03). One record per source event.
 */
@Component
public class CessionEventHandler implements DomainEventHandler {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String ACTOR = "system";

    private final EntityManager em;
    private final Outbox outbox;

    public CessionEventHandler(EntityManager em, Outbox outbox) {
        this.em = em;
        this.outbox = outbox;
    }

    @Override
    public String name() {
        return "reinsurance.cession";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(Events.PREMIUM_RECEIVED, Events.CLAIM_PAID);
    }

    @Override
    public void handle(DomainEvent event) {
        if (Events.PREMIUM_RECEIVED.equals(event.eventType())) {
            cede(event);
        } else if (Events.CLAIM_PAID.equals(event.eventType())) {
            recover(event);
        }
    }

    private void cede(DomainEvent event) {
        if (!em.createQuery("select c.id from Cession c where c.sourceEventId = :id", Long.class)
                .setParameter("id", event.eventId()).setMaxResults(1).getResultList().isEmpty()) {
            return;
        }
        String policyRef = event.text("policyRef");
        String product = event.text("product");
        LocalDate today = LocalDate.now();
        Optional<Treaty> treaty = em.createQuery("select t from Treaty t where t.status = :approved"
                        + " and t.effectiveFrom <= :today and t.effectiveTo >= :today order by t.approvedAt asc, t.id asc",
                        Treaty.class)
                .setParameter("approved", Treaty.APPROVED)
                .setParameter("today", today)
                .getResultList()
                .stream()
                .filter(t -> t.covers(product))
                .findFirst();
        if (treaty.isEmpty()) {
            return;
        }
        Optional<InsurancePolicy> policy = em.createQuery("select p from InsurancePolicy p where p.ref = :ref",
                        InsurancePolicy.class)
                .setParameter("ref", policyRef)
                .getResultStream()
                .findFirst();
        if (policy.isEmpty()) {
            return;
        }
        BigDecimal sumAssured = BigDecimal.valueOf(policy.get().getAmount()).setScale(2);
        BigDecimal share = share(treaty.get(), sumAssured);
        if (share.signum() <= 0) {
            return;
        }
        BigDecimal premium = event.amount("amount").setScale(2, RoundingMode.HALF_UP);
        BigDecimal cededPremium = premium.multiply(share).setScale(2, RoundingMode.HALF_UP);
        BigDecimal cededSum = sumAssured.multiply(share).setScale(2, RoundingMode.HALF_UP);
        Cession cession = new Cession(treaty.get(), policyRef, product, event.eventId(), sumAssured, cededSum, premium,
                cededPremium, share);
        em.persist(cession);
        em.flush();
        cession.numbered();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cessionRef", cession.getRef());
        data.put("treatyRef", treaty.get().getRef());
        data.put("policyRef", policyRef);
        data.put("product", product);
        data.put("cededPremium", cededPremium.toPlainString());
        outbox.publish(Events.RISK_CEDED, cession.getRef(), data, ACTOR, event.correlationId());
    }

    /** Quota share: the percentage. Surplus: the part of the sum assured above the retention (zero at or below it). */
    static BigDecimal share(Treaty treaty, BigDecimal sumAssured) {
        if (Treaty.QUOTA_SHARE.equals(treaty.getTreatyType())) {
            return treaty.getCededPercent().divide(HUNDRED, 6, RoundingMode.HALF_UP);
        }
        if (sumAssured.signum() <= 0 || treaty.getRetention() == null
                || sumAssured.compareTo(treaty.getRetention()) <= 0) {
            return BigDecimal.ZERO.setScale(6);
        }
        return sumAssured.subtract(treaty.getRetention()).divide(sumAssured, 6, RoundingMode.HALF_UP);
    }

    private void recover(DomainEvent event) {
        if (!em.createQuery("select r.id from Recovery r where r.sourceEventId = :id", Long.class)
                .setParameter("id", event.eventId()).setMaxResults(1).getResultList().isEmpty()) {
            return;
        }
        String policyRef = event.text("policyRef");
        String claimRef = event.text("claimRef");
        List<Cession> latest = em.createQuery("select c from Cession c join fetch c.treaty where c.policyRef = :ref"
                        + " order by c.createdAt desc, c.id desc", Cession.class)
                .setParameter("ref", policyRef)
                .setMaxResults(1)
                .getResultList();
        if (latest.isEmpty() || claimRef == null) {
            return;
        }
        Cession cession = latest.get(0);
        BigDecimal claimAmount = event.amount("amount").setScale(2, RoundingMode.HALF_UP);
        BigDecimal recoveryAmount = claimAmount.multiply(cession.getCededShare()).setScale(2, RoundingMode.HALF_UP);
        Recovery recovery = new Recovery(cession.getTreaty(), claimRef, policyRef, event.eventId(), claimAmount,
                cession.getCededShare(), recoveryAmount);
        em.persist(recovery);
        em.flush();
        recovery.numbered();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recoveryRef", recovery.getRef());
        data.put("treatyRef", cession.getTreaty().getRef());
        data.put("claimRef", claimRef);
        data.put("policyRef", policyRef);
        data.put("amount", recoveryAmount.toPlainString());
        outbox.publish(Events.RECOVERY_RECOGNISED, recovery.getRef(), data, ACTOR, event.correlationId());
    }
}
