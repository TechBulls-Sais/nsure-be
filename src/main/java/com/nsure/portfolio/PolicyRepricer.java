package com.nsure.portfolio;

import com.nsure.rating.RateTableService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spec 01 BR-9 / 06 Q-1: the premium is recalculated server-side immediately before charging, from the policy's own
 * lives, through the same pricing function the quote used. A client-supplied amount is never trusted. A flat-priced
 * plan keeps its tier premium; a policy with no tier keeps the premium it was issued with.
 */
@Component
public class PolicyRepricer {

    private final EntityManager em;
    private final RateTableService rates;
    private final WorkflowHistory history;

    public PolicyRepricer(EntityManager em, RateTableService rates, WorkflowHistory history) {
        this.em = em;
        this.rates = rates;
        this.history = history;
    }

    @Transactional
    public BigDecimal reprice(InsurancePolicy policy, String actor) {
        if (policy.getTier() == null || policy.getTier().isBlank()) {
            return policy.getPremium();
        }
        CoverTier tier = em.createQuery("select t from CoverTier t where t.product.name = :product and t.name = :tier",
                        CoverTier.class)
                .setParameter("product", policy.getProduct())
                .setParameter("tier", policy.getTier())
                .getResultStream()
                .findFirst()
                .orElse(null);
        if (tier == null) {
            return policy.getPremium();
        }
        String self = em.createQuery(
                        "select r.relationshipName from RelationshipType r where r.selfRelationship = true", String.class)
                .setMaxResults(1).getResultStream().findFirst().orElse("Main Insured");
        List<LocalDate> births = policy.getCustomerRef() == null ? List.of() : em.createQuery(
                        "select c.dateOfBirth from CustomerAccount c where c.ref = :ref", LocalDate.class)
                .setParameter("ref", policy.getCustomerRef())
                .getResultList();
        LocalDate dateOfBirth = births.isEmpty() ? null : births.get(0);
        List<RateTableService.LifeToPrice> lives = new ArrayList<>();
        lives.add(new RateTableService.LifeToPrice(self, policy.getCustomerName(), dateOfBirth));
        for (PolicyLifeCover life : em.createQuery("select l from PolicyLifeCover l where l.policy = :policy"
                        + " order by l.displayOrder", PolicyLifeCover.class)
                .setParameter("policy", policy).getResultList()) {
            if (!self.equalsIgnoreCase(life.getLifeType())) {
                lives.add(new RateTableService.LifeToPrice(life.getLifeType(), life.getFullName(), life.getDateOfBirth()));
            }
        }
        Optional<RateTableService.PolicyPrice> price = rates.pricePolicy(tier, lives, null);
        BigDecimal premium = price.map(RateTableService.PolicyPrice::monthlyTotal)
                .orElse(BigDecimal.valueOf(tier.getPremium()));
        if (policy.getPremium() == null || premium.compareTo(policy.getPremium()) != 0) {
            history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Re-priced",
                    policy.getStatus(), policy.getStatus(), "Premium re-priced before payment: BWP "
                            + policy.getPremium() + " → BWP " + premium
                            + price.map(p -> " (" + p.rateTableVersion() + ")").orElse(""), actor);
            policy.setPremium(premium);
            price.ifPresent(p -> policy.setRateTableVersion(p.rateTableVersion()));
        }
        return premium;
    }
}
