package com.nsure.portfolio;

import com.nsure.common.Money;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claim money that is not yet paid (SH05): the case reserve, the per-life and policy benefit limits, the evidence
 * checklist and related claims on the same policy.
 */
@Component
public class ClaimFinancials {

    /** Benefit limits and their use for one claim's covered life and policy. */
    public record Limits(String lifeKey, BigDecimal lifeLimit, BigDecimal lifeUsed, BigDecimal policyLimit,
            BigDecimal policyUsed) {

        BigDecimal lifeRemaining() {
            return lifeLimit.subtract(lifeUsed);
        }

        BigDecimal policyRemaining() {
            return policyLimit.subtract(policyUsed);
        }
    }

    /** Claims on the same policy within this many days of each other, for the same life, may be one event. */
    static final int DUPLICATE_WINDOW_DAYS = 7;

    private final EntityManager em;
    private final Outbox outbox;

    public ClaimFinancials(EntityManager em, Outbox outbox) {
        this.em = em;
        this.outbox = outbox;
    }

    // ── reserve ──────────────────────────────────────────────────────────────────────────────────

    /** Moves the case reserve to {@code balance}, recording why (NS-SH05-003). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(InsuranceClaim claim, BigDecimal balance, String reason, String actor) {
        BigDecimal before = claim.getReserveAmount() == null ? BigDecimal.ZERO : claim.getReserveAmount();
        BigDecimal after = balance.setScale(2, java.math.RoundingMode.HALF_UP);
        if (before.compareTo(after) == 0) {
            return;
        }
        claim.setReserveAmount(after);
        em.persist(new ClaimLedgers.ReserveMovement(claim.getRef(), after.subtract(before), after, reason,
                actor == null ? WorkflowHistory.SYSTEM : actor));
    }

    public List<ClaimLedgers.ReserveMovement> movements(String claimRef) {
        return em.createQuery("select m from ClaimReserveMovement m where m.claimRef = :ref order by m.id",
                ClaimLedgers.ReserveMovement.class).setParameter("ref", claimRef).getResultList();
    }

    // ── limits ───────────────────────────────────────────────────────────────────────────────────

    public Limits limits(InsuranceClaim claim, InsurancePolicy policy) {
        String lifeKey = lifeKey(claim);
        Long lifeId = lifeId(claim);
        BigDecimal lifeLimit = policy.getAmount();
        BigDecimal livesTotal = BigDecimal.ZERO;
        for (PolicyLifeCover life : lives(policy)) {
            livesTotal = livesTotal.add(life.getCoverAmount() == null ? BigDecimal.ZERO : life.getCoverAmount());
            if (life.getId().equals(lifeId) && life.getCoverAmount() != null && life.getCoverAmount().signum() > 0) {
                lifeLimit = life.getCoverAmount();
            }
        }
        BigDecimal policyLimit = policy.getAmount().max(livesTotal);
        return new Limits(lifeKey, lifeLimit, used(policy.getRef(), lifeKey), policyLimit, used(policy.getRef(), null));
    }

    /**
     * Reserves {@code amount} against the claim's life and policy limits (NS-SH05-005, AT-SH05-02). The policy row
     * is locked first, so two approvals on one policy run one after the other and cannot both use the same room.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveLimit(InsuranceClaim claim, InsurancePolicy policy, BigDecimal amount, String actor) {
        em.lock(policy, LockModeType.PESSIMISTIC_WRITE);
        Limits limits = limits(claim, policy);
        if (amount.compareTo(limits.lifeRemaining()) > 0) {
            throw new IllegalArgumentException("A payout of " + Money.display(amount) + " is more than the "
                    + Money.display(limits.lifeRemaining()) + " left of " + nameOf(claim) + "'s benefit limit.");
        }
        if (amount.compareTo(limits.policyRemaining()) > 0) {
            throw new IllegalArgumentException("A payout of " + Money.display(amount) + " is more than the "
                    + Money.display(limits.policyRemaining()) + " left of policy " + policy.getRef() + "'s limit.");
        }
        em.persist(new ClaimLedgers.LimitEntry(policy.getRef(), limits.lifeKey(), claim.getRef(),
                ClaimLedgers.LimitEntry.RESERVED, amount, actor == null ? WorkflowHistory.SYSTEM : actor));
    }

    /** What the claim still holds reserved against its limits (reserved minus released). */
    public BigDecimal reservedLimit(String claimRef) {
        BigDecimal reserved = BigDecimal.ZERO;
        for (ClaimLedgers.LimitEntry e : entries(claimRef)) {
            reserved = ClaimLedgers.LimitEntry.RESERVED.equals(e.getEntry()) ? reserved.add(e.getAmount())
                    : ClaimLedgers.LimitEntry.RELEASED.equals(e.getEntry()) ? reserved.subtract(e.getAmount()) : reserved;
        }
        return reserved;
    }

    /** Gives back a reservation that will not be paid (an approval set aside on reopening, NS-SH05-005). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseLimit(InsuranceClaim claim, String actor) {
        BigDecimal reserved = reservedLimit(claim.getRef());
        if (reserved.signum() > 0) {
            em.persist(new ClaimLedgers.LimitEntry(claim.getPolicyRef(), lifeKey(claim), claim.getRef(),
                    ClaimLedgers.LimitEntry.RELEASED, reserved, actor == null ? WorkflowHistory.SYSTEM : actor));
        }
    }

    /** The settled payout uses the limit for good: the reservation is released and the paid amount consumed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(InsuranceClaim claim, String actor) {
        BigDecimal reserved = BigDecimal.ZERO;
        for (ClaimLedgers.LimitEntry e : entries(claim.getRef())) {
            reserved = ClaimLedgers.LimitEntry.RESERVED.equals(e.getEntry()) ? reserved.add(e.getAmount())
                    : ClaimLedgers.LimitEntry.RELEASED.equals(e.getEntry()) ? reserved.subtract(e.getAmount()) : reserved;
        }
        String who = actor == null ? WorkflowHistory.SYSTEM : actor;
        if (reserved.signum() > 0) {
            em.persist(new ClaimLedgers.LimitEntry(claim.getPolicyRef(), lifeKey(claim), claim.getRef(),
                    ClaimLedgers.LimitEntry.RELEASED, reserved, who));
        }
        em.persist(new ClaimLedgers.LimitEntry(claim.getPolicyRef(), lifeKey(claim), claim.getRef(),
                ClaimLedgers.LimitEntry.CONSUMED, claim.getPayoutAmount(), who));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("claimRef", claim.getRef());
        data.put("policyRef", claim.getPolicyRef());
        data.put("lifeKey", lifeKey(claim));
        data.put("amount", claim.getPayoutAmount().toPlainString());
        outbox.publish(Events.BENEFIT_LIMIT_CONSUMED, claim.getPolicyRef(), data, who);
    }

    /**
     * A settled payout came back (§09, NS-SH05-005): the consumption is reversed by a corrective entry that names the
     * returned instruction, and the amount is reserved again, because the benefit is still owed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverseConsumption(InsuranceClaim claim, String instructionRef, String actor) {
        String who = actor == null ? WorkflowHistory.SYSTEM : actor;
        BigDecimal consumed = BigDecimal.ZERO;
        for (ClaimLedgers.LimitEntry e : entries(claim.getRef())) {
            consumed = ClaimLedgers.LimitEntry.CONSUMED.equals(e.getEntry()) ? consumed.add(e.getAmount())
                    : ClaimLedgers.LimitEntry.REVERSED.equals(e.getEntry()) ? consumed.subtract(e.getAmount()) : consumed;
        }
        if (consumed.signum() <= 0) {
            return;
        }
        em.persist(new ClaimLedgers.LimitEntry(claim.getPolicyRef(), lifeKey(claim), claim.getRef(),
                ClaimLedgers.LimitEntry.REVERSED, consumed, who));
        em.persist(new ClaimLedgers.LimitEntry(claim.getPolicyRef(), lifeKey(claim), claim.getRef(),
                ClaimLedgers.LimitEntry.RESERVED, consumed, who));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("claimRef", claim.getRef());
        data.put("policyRef", claim.getPolicyRef());
        data.put("lifeKey", lifeKey(claim));
        data.put("amount", consumed.negate().toPlainString());
        data.put("reversalOf", instructionRef);
        outbox.publish(Events.BENEFIT_LIMIT_CONSUMED, claim.getPolicyRef(), data, who);
    }

    /** Limit entries of a claim, oldest first, for its ledger view. */
    public List<ClaimLedgers.LimitEntry> limitEntries(String claimRef) {
        return entries(claimRef);
    }

    // ── evidence and related claims ──────────────────────────────────────────────────────────────

    /** The claim type's evidence checklist, each item marked received when a current document of that type is on file. */
    public List<PortfolioViews.EvidenceItem> evidence(InsuranceClaim claim) {
        List<String> received = claim.getId() == null ? List.of() : em.createQuery(
                        "select d.documentType from ClaimDocument d where d.claimId = :id and d.status <> 'SUPERSEDED'",
                        String.class)
                .setParameter("id", claim.getId()).getResultList();
        List<PortfolioViews.EvidenceItem> items = new ArrayList<>();
        for (ClaimLedgers.EvidenceRequirement r : em.createQuery("select r from ClaimEvidenceRequirement r"
                        + " where r.claimType = :type and (r.product is null or r.product = :product)"
                        + " order by r.displayOrder", ClaimLedgers.EvidenceRequirement.class)
                .setParameter("type", claim.getClaimType()).setParameter("product", claim.getProduct())
                .getResultList()) {
            items.add(new PortfolioViews.EvidenceItem(r.getDocumentType(), r.isMandatory(),
                    received.contains(r.getDocumentType())));
        }
        return items;
    }

    public List<String> missingMandatory(InsuranceClaim claim) {
        return evidence(claim).stream().filter(i -> i.mandatory() && !i.received())
                .map(PortfolioViews.EvidenceItem::documentType).toList();
    }

    /** Other claims on the policy; one for the same life within a week of this event may be the same event. */
    public List<PortfolioViews.RelatedClaim> related(InsuranceClaim claim) {
        if (claim.getPolicyRef() == null) {
            return List.of();
        }
        LocalDate event = date(claim.getEventDate());
        List<PortfolioViews.RelatedClaim> related = new ArrayList<>();
        for (InsuranceClaim other : em.createQuery("select c from InsuranceClaim c where c.policyRef = :policy"
                        + " and c.ref <> :ref order by c.id", InsuranceClaim.class)
                .setParameter("policy", claim.getPolicyRef()).setParameter("ref", claim.getRef()).getResultList()) {
            LocalDate otherEvent = date(other.getEventDate());
            boolean sameLife = lifeKey(other).equals(lifeKey(claim));
            boolean close = event != null && otherEvent != null
                    && Math.abs(ChronoUnit.DAYS.between(event, otherEvent)) <= DUPLICATE_WINDOW_DAYS;
            related.add(new PortfolioViews.RelatedClaim(other.getRef(), other.getStatus(), other.getEventDate(),
                    nameOf(other), other.getClaimType(), sameLife && close));
        }
        return related;
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    List<PolicyLifeCover> lives(InsurancePolicy policy) {
        return em.createQuery("select l from PolicyLifeCover l where l.policy = :policy order by l.displayOrder",
                PolicyLifeCover.class).setParameter("policy", policy).getResultList();
    }

    /**
     * The person a claim is for, keyed by person rather than by how the claim named them (NS-IL04-004, NS-SH05-005):
     * a claim that names no life is for the main insured, which is the same counter as a claim naming them. Only a
     * policy with no covered-life rows uses "PRINCIPAL".
     */
    String lifeKey(InsuranceClaim claim) {
        Long life = lifeId(claim);
        return life == null ? "PRINCIPAL" : "LIFE-" + life;
    }

    /** The covered-life row a claim is for: the one it names, else the policy's main insured life, if any. */
    private Long lifeId(InsuranceClaim claim) {
        if (claim.getCoveredLifeId() != null || claim.getPolicyRef() == null) {
            return claim.getCoveredLifeId();
        }
        return em.createQuery("select l.id from PolicyLifeCover l where l.policy.ref = :ref"
                        + " and l.relationship.selfRelationship = true order by l.id", Long.class)
                .setParameter("ref", claim.getPolicyRef()).setMaxResults(1).getResultStream().findFirst().orElse(null);
    }

    private static String nameOf(InsuranceClaim claim) {
        return claim.getCoveredLifeName() == null ? claim.getCustomerName() : claim.getCoveredLifeName();
    }

    private List<ClaimLedgers.LimitEntry> entries(String claimRef) {
        return em.createQuery("select e from ClaimLimitEntry e where e.claimRef = :ref order by e.id",
                ClaimLedgers.LimitEntry.class).setParameter("ref", claimRef).getResultList();
    }

    private BigDecimal used(String policyRef, String lifeKey) {
        BigDecimal total = BigDecimal.ZERO;
        for (Object[] row : em.createQuery("select e.entry, sum(e.amount) from ClaimLimitEntry e"
                        + " where e.policyRef = :policy and (:life is null or e.lifeKey = :life) group by e.entry",
                        Object[].class)
                .setParameter("policy", policyRef).setParameter("life", lifeKey).getResultList()) {
            BigDecimal sum = (BigDecimal) row[1];
            total = ClaimLedgers.LimitEntry.RELEASED.equals(row[0]) || ClaimLedgers.LimitEntry.REVERSED.equals(row[0])
                    ? total.subtract(sum) : total.add(sum);
        }
        return total;
    }

    private static LocalDate date(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }
}
