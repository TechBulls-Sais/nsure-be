package com.nsure.portfolio;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relationship-specific consent and access (NS-IL04-006). The policyholder sees the contract, but an adult insured
 * life's restricted medical evidence on a claim is theirs: the policyholder may open it only with that life's own
 * recorded consent (or when the policyholder uploaded it themselves). Staff access is governed by permissions.
 */
@Component
public class LifeConsents {

    /** Claim document types that hold medical evidence (restricted to the life concerned). */
    public static final Set<String> MEDICAL_TYPES = Set.of("Medical report", "Hospital bill", "Post-mortem report",
            "Attending doctor's report", "Medical examination report");

    private final EntityManager em;
    private final int adultAge;

    public LifeConsents(EntityManager em, @Value("${nsure.privacy.adult-age:18}") int adultAge) {
        this.em = em;
        this.adultAge = adultAge;
    }

    /** The decision that applies now for the life and scope, or null when none was ever recorded. */
    public LifeConsent current(Long lifeId, String scope) {
        return em.createQuery("select c from LifeConsent c where c.lifeId = :life and c.scope = :scope order by c.id desc",
                        LifeConsent.class)
                .setParameter("life", lifeId).setParameter("scope", scope).setMaxResults(1).getResultStream()
                .findFirst().orElse(null);
    }

    public List<LifeConsent> history(String policyRef) {
        return em.createQuery("select c from LifeConsent c where c.policyRef = :ref order by c.id desc", LifeConsent.class)
                .setParameter("ref", policyRef).getResultList();
    }

    /** Records the life's own decision; the earlier rows stay as history. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LifeConsent record(InsurancePolicy policy, PolicyLifeCover life, String status, String evidence, String actor) {
        if (life.getPolicy() == null || !life.getPolicy().getId().equals(policy.getId())) {
            throw new IllegalArgumentException("That life is not on policy " + policy.getRef() + ".");
        }
        if (life.isMainInsured()) {
            throw new IllegalArgumentException("The main insured is the policyholder's own life; no consent is needed.");
        }
        if (!Set.of(LifeConsent.GRANTED, LifeConsent.WITHDRAWN).contains(status)) {
            throw new IllegalArgumentException("status must be GRANTED or WITHDRAWN.");
        }
        if (evidence == null || evidence.trim().length() < 5) {
            throw new IllegalArgumentException("Say how the insured life gave or withdrew consent (at least 5 characters).");
        }
        LifeConsent row = new LifeConsent(policy.getRef(), life.getId(), LifeConsent.MEDICAL_TO_POLICYHOLDER, status,
                evidence.trim().length() > 300 ? evidence.trim().substring(0, 300) : evidence.trim(), actor);
        em.persist(row);
        return row;
    }

    /** Whether the life is an adult today (age of majority, demo default 18): only adults' consent is needed. */
    public boolean isAdult(PolicyLifeCover life) {
        return life.getDateOfBirth() != null && Period.between(life.getDateOfBirth(), LocalDate.now()).getYears() >= adultAge;
    }

    /** Why the policyholder may not open this document on their own claim, or null when they may. */
    public String withheldFromPolicyholder(InsuranceClaim claim, String documentType, String uploadedBy,
            CustomerAccount policyholder) {
        if (documentType == null || !MEDICAL_TYPES.contains(documentType) || claim.getCoveredLifeId() == null) {
            return null;
        }
        if (uploadedBy != null && uploadedBy.equalsIgnoreCase(policyholder.getLoginUsername())) {
            return null;
        }
        PolicyLifeCover life = em.find(PolicyLifeCover.class, claim.getCoveredLifeId());
        if (life == null || life.isMainInsured() || !isAdult(life)) {
            return null;
        }
        LifeConsent consent = current(life.getId(), LifeConsent.MEDICAL_TO_POLICYHOLDER);
        if (consent != null && LifeConsent.GRANTED.equals(consent.getStatus())) {
            return null;
        }
        return "This " + documentType.toLowerCase(java.util.Locale.ROOT) + " is " + life.getFullName()
                + "'s medical evidence. As an adult insured life, their consent is needed before it is shared with the"
                + " policyholder.";
    }
}
