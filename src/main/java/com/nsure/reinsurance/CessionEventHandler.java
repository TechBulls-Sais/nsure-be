package com.nsure.reinsurance;

import com.nsure.integration.DomainEvent;
import com.nsure.integration.DomainEventHandler;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.portfolio.InsurancePolicy;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Cedes risk from events (SH07).
 *
 * <ul>
 *   <li><b>Exposure</b> (NS-SH07-002/003): at issue, and again on every benefit change, the policy's ceded sum is
 *       worked out per life under the automatic treaty in force on the event date — the life's other policies under
 *       that treaty count towards the surplus retention and the treaty's capacity. The part above capacity is referred
 *       for facultative placement. A change supersedes the previous version; nothing is overwritten. No approved
 *       treaty, no cession (AT-SH07-01).</li>
 *   <li><b>Premium cessions</b>: each premium received is ceded at the share of the exposure in force (and of an
 *       accepted facultative arrangement), keeping its receipt. A returned premium or a premium refund gets a negative
 *       adjustment linked to the cession it adjusts, once (AT-SH07-02).</li>
 *   <li><b>Recoveries</b> (NS-SH07-005): when a claim's liability is approved, the reinsurer's share at the cover in
 *       force on the claim's event date is expected, separately from the customer's payout (AT-SH07-03); payment at a
 *       different amount re-states it.</li>
 * </ul>
 * One record per source event, so a redelivered event adds nothing.
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
        return Set.of(Events.PREMIUM_RECEIVED, Events.CLAIM_PAID, Events.PAYMENT_RETURNED, Events.POLICY_ISSUED,
                Events.POLICY_CHANGED, Events.POLICY_CANCELLED, Events.POLICY_TERMINATED, Events.CLAIM_APPROVED,
                Events.REFUND_SETTLED);
    }

    @Override
    public void handle(DomainEvent event) {
        switch (event.eventType()) {
            case Events.POLICY_ISSUED -> issued(event);
            case Events.POLICY_CHANGED -> changed(event);
            case Events.POLICY_CANCELLED, Events.POLICY_TERMINATED -> ended(event);
            case Events.PREMIUM_RECEIVED -> cede(event);
            case Events.PAYMENT_RETURNED -> returned(event);
            case Events.REFUND_SETTLED -> refunded(event);
            case Events.CLAIM_APPROVED -> recover(event, true);
            case Events.CLAIM_PAID -> recover(event, false);
            default -> {
            }
        }
    }

    // ── exposure ─────────────────────────────────────────────────────────────────────────────────

    private void issued(DomainEvent event) {
        if (exposureExists(event.eventId())) {
            return;
        }
        expose(event.text("policyRef"), event.occurredAt().toLocalDate(), event, "Issued");
    }

    /** A benefit change (endorsement, paid-up): a new exposure version when the sum assured moved. */
    private void changed(DomainEvent event) {
        if (exposureExists(event.eventId())) {
            return;
        }
        String policyRef = event.text("policyRef");
        Exposure current = live(policyRef);
        InsurancePolicy policy = policy(policyRef);
        if (current == null || policy == null || policy.getAmount() == null
                || policy.getAmount().setScale(2, RoundingMode.HALF_UP).compareTo(current.getSumAssured()) == 0) {
            return;
        }
        LocalDate on = date(event.text("effectiveDate"), event.occurredAt().toLocalDate());
        if (on.isBefore(current.getEffectiveFrom())) {
            on = current.getEffectiveFrom();
        }
        String what = event.text("endorsementType") == null ? "Benefit change" : event.text("endorsementType");
        expose(policyRef, on, event, what + (event.text("endorsementRef") == null ? "" : " " + event.text("endorsementRef"))
                + ": sum assured " + current.getSumAssured().toPlainString() + " to "
                + policy.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    /** Cancellation or termination: the exposure ends; its history stays. */
    private void ended(DomainEvent event) {
        Exposure current = live(event.text("policyRef"));
        if (current != null) {
            LocalDate on = date(event.text("terminatedOn"), event.occurredAt().toLocalDate());
            current.end(Exposure.ENDED, on.isBefore(current.getEffectiveFrom()) ? current.getEffectiveFrom() : on);
        }
    }

    /**
     * Works out and records the policy's exposure on {@code on}, superseding the live version. Per life: the life's
     * other live exposures under the same treaty use up the surplus retention and the treaty capacity first.
     */
    Exposure expose(String policyRef, LocalDate on, DomainEvent event, String reason) {
        InsurancePolicy policy = policy(policyRef);
        if (policy == null || policy.getAmount() == null) {
            return null;
        }
        Exposure current = live(policyRef);
        BigDecimal sumAssured = policy.getAmount().setScale(2, RoundingMode.HALF_UP);
        String customer = policy.getCustomerRef();
        Treaty treaty = automaticTreaty(policy.getProduct(), on).orElse(null);
        BigDecimal othersSum = BigDecimal.ZERO;
        BigDecimal othersRetained = BigDecimal.ZERO;
        BigDecimal othersCeded = BigDecimal.ZERO;
        if (treaty != null && customer != null) {
            for (Exposure other : em.createQuery("select e from Exposure e where e.customerRef = :customer"
                            + " and e.treaty = :treaty and e.status = :active and e.policyRef <> :ref", Exposure.class)
                    .setParameter("customer", customer).setParameter("treaty", treaty)
                    .setParameter("active", Exposure.ACTIVE).setParameter("ref", policyRef).getResultList()) {
                othersSum = othersSum.add(other.getSumAssured());
                othersRetained = othersRetained.add(other.getRetainedSum());
                othersCeded = othersCeded.add(other.getCededSum());
            }
        }
        BigDecimal ceded = BigDecimal.ZERO.setScale(2);
        BigDecimal excess = BigDecimal.ZERO.setScale(2);
        String status = Exposure.ACTIVE;
        String why = reason;
        if (treaty == null) {
            status = Exposure.NO_TREATY;
            why += "; no approved treaty covers " + policy.getProduct() + " on " + on + ", so nothing is ceded";
        } else if (Treaty.QUOTA_SHARE.equals(treaty.getTreatyType())) {
            ceded = sumAssured.multiply(treaty.getCededPercent()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        } else {
            BigDecimal room = treaty.getRetention().subtract(othersRetained).max(BigDecimal.ZERO);
            ceded = sumAssured.subtract(sumAssured.min(room)).setScale(2, RoundingMode.HALF_UP);
        }
        if (treaty != null && treaty.getCapacity() != null) {
            BigDecimal room = treaty.getCapacity().subtract(othersCeded).max(BigDecimal.ZERO);
            if (ceded.compareTo(room) > 0) {
                excess = ceded.subtract(room).setScale(2, RoundingMode.HALF_UP);
                ceded = room.setScale(2, RoundingMode.HALF_UP);
                why += "; " + excess.toPlainString() + " above " + treaty.getRef() + "'s capacity of "
                        + treaty.getCapacity().toPlainString() + " per life is referred for facultative placement";
            }
        }
        BigDecimal share = sumAssured.signum() <= 0 ? BigDecimal.ZERO.setScale(6)
                : ceded.divide(sumAssured, 6, RoundingMode.HALF_UP);
        if (current != null) {
            current.end(Exposure.SUPERSEDED, on.isBefore(current.getEffectiveFrom()) ? current.getEffectiveFrom() : on);
            em.flush();
        }
        Exposure exposure = new Exposure(policyRef, customer, policy.getProduct(),
                current == null ? 1 : current.getVersion() + 1, sumAssured, othersSum.add(sumAssured),
                sumAssured.subtract(ceded), ceded, share, treaty, status, on, event.eventId(), event.eventType(), why,
                current);
        em.persist(exposure);
        em.flush();
        exposure.numbered();
        Facultative kept = current == null ? null : current.getFacultative();
        if (kept != null && !Facultative.DECLINED.equals(kept.getStatus())) {
            exposure.facultative(kept);
        } else if (excess.signum() > 0) {
            Facultative referral = new Facultative(policyRef, customer, policy.getProduct(), sumAssured,
                    othersSum.add(sumAssured), excess, treaty, "Life " + customer + " would cede "
                    + othersCeded.add(ceded).add(excess).toPlainString() + " under " + treaty.getRef()
                    + ", above its capacity of " + treaty.getCapacity().toPlainString() + " (" + reason + ")");
            em.persist(referral);
            em.flush();
            referral.numbered();
            exposure.facultative(referral);
        }
        return exposure;
    }

    // ── premium cessions and adjustments ─────────────────────────────────────────────────────────

    private void cede(DomainEvent event) {
        if (cessionExists(event.eventId())) {
            return;
        }
        String policyRef = event.text("policyRef");
        LocalDate on = event.occurredAt().toLocalDate();
        Exposure exposure = live(policyRef);
        if (exposure == null && !em.createQuery("select e.id from Exposure e where e.policyRef = :ref", Long.class)
                .setParameter("ref", policyRef).setMaxResults(1).getResultList().isEmpty()) {
            return; // ended: nothing in force to cede
        }
        if (exposure == null) {
            // Issued before exposures were recorded: worked out now, under the treaty in force today.
            exposure = expose(policyRef, on, event, "Calculated on the first premium after exposures were recorded");
        }
        if (exposure == null || !Exposure.ACTIVE.equals(exposure.getStatus())) {
            return;
        }
        BigDecimal premium = event.amount("amount").setScale(2, RoundingMode.HALF_UP);
        String receiptRef = event.text("receiptRef");
        if (exposure.getTreaty() != null && exposure.getCededShare().signum() > 0) {
            Cession cession = new Cession(exposure.getTreaty(), policyRef, exposure.getProduct(), event.eventId(),
                    exposure.getSumAssured(), exposure.getCededSum(), premium,
                    premium.multiply(exposure.getCededShare()).setScale(2, RoundingMode.HALF_UP),
                    exposure.getCededShare()).trace(receiptRef, null, exposure, on, "PREMIUM");
            persistCeded(cession, event, null, null);
        }
        Facultative fac = exposure.getFacultative();
        if (fac != null && Facultative.ACCEPTED.equals(fac.getStatus()) && fac.getFacTreaty() != null
                && exposure.getSumAssured().signum() > 0) {
            String facSource = derived(event.eventId(), "FAC");
            if (!cessionExists(facSource)) {
                BigDecimal facShare = fac.getAcceptedSum().divide(exposure.getSumAssured(), 6, RoundingMode.HALF_UP);
                Cession cession = new Cession(fac.getFacTreaty(), policyRef, exposure.getProduct(), facSource,
                        exposure.getSumAssured(), fac.getAcceptedSum(), premium,
                        premium.multiply(facShare).setScale(2, RoundingMode.HALF_UP), facShare)
                        .trace(receiptRef, null, exposure, on, "PREMIUM");
                persistCeded(cession, event, null, null);
            }
        }
    }

    /** AT-SH07-02: the cessions of the returned receipt get a linked negative adjustment each, once. */
    private void returned(DomainEvent event) {
        String receiptRef = event.text("receiptRef");
        List<Cession> originals = receiptRef == null ? List.of() : em.createQuery("select c from Cession c join fetch"
                        + " c.treaty where c.policyRef = :ref and c.receiptRef = :receipt and c.adjusts is null"
                        + " order by c.id", Cession.class)
                .setParameter("ref", event.text("policyRef")).setParameter("receipt", receiptRef).getResultList();
        if (originals.isEmpty() && receiptRef == null) {
            // Older events name no receipt: the latest premium cession on the policy.
            originals = em.createQuery("select c from Cession c join fetch c.treaty where c.policyRef = :ref"
                    + " and c.adjusts is null order by c.id desc", Cession.class)
                    .setParameter("ref", event.text("policyRef")).setMaxResults(1).getResultList();
        }
        for (Cession original : originals) {
            adjust(original, original.getPremium(), event, Events.PAYMENT_RETURNED);
        }
    }

    /** A premium refund the refund controls paid on the policy (ledgerAmount > 0): its ceded share comes back. */
    private void refunded(DomainEvent event) {
        String policyRef = event.text("policyRef");
        BigDecimal refund = event.amount("ledgerAmount");
        if (policyRef == null || policyRef.isBlank() || refund.signum() <= 0) {
            return;
        }
        Map<Long, Cession> latestPerTreaty = new LinkedHashMap<>();
        for (Cession c : em.createQuery("select c from Cession c join fetch c.treaty where c.policyRef = :ref"
                + " and c.adjusts is null order by c.id desc", Cession.class).setParameter("ref", policyRef)
                .getResultList()) {
            latestPerTreaty.putIfAbsent(c.getTreaty().getId(), c);
        }
        for (Cession original : latestPerTreaty.values()) {
            adjust(original, refund.setScale(2, RoundingMode.HALF_UP), event, "REFUND");
        }
    }

    private void adjust(Cession original, BigDecimal premium, DomainEvent event, String reason) {
        String source = derived(event.eventId(), original.getRef());
        // An adjustment recorded before adjustments were linked used the event id itself.
        if (cessionExists(source) || cessionExists(event.eventId())) {
            return;
        }
        BigDecimal negative = premium.negate();
        Cession adjustment = new Cession(original.getTreaty(), original.getPolicyRef(), original.getProduct(), source,
                original.getSumAssured(), BigDecimal.ZERO.setScale(2), negative,
                negative.multiply(original.getCededShare()).setScale(2, RoundingMode.HALF_UP), original.getCededShare())
                .trace(original.getReceiptRef(), original, original.getExposure(), event.occurredAt().toLocalDate(),
                        reason);
        // The adjustment is a negative RiskCeded: the ceded premium posting reverses for the returned premium.
        persistCeded(adjustment, event, original.getRef(), reason);
    }

    private void persistCeded(Cession cession, DomainEvent event, String adjusts, String reason) {
        em.persist(cession);
        em.flush();
        cession.numbered();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cessionRef", cession.getRef());
        data.put("treatyRef", cession.getTreaty().getRef());
        data.put("policyRef", cession.getPolicyRef());
        data.put("product", cession.getProduct());
        data.put("cededPremium", cession.getCededPremium().toPlainString());
        if (cession.getReceiptRef() != null) {
            data.put("receiptRef", cession.getReceiptRef());
        }
        if (adjusts != null) {
            data.put("adjusts", adjusts);
            data.put("reason", reason);
        }
        outbox.publish(Events.RISK_CEDED, cession.getRef(), data, ACTOR, event.correlationId());
    }

    /** Quota share: the percentage. Surplus: the part of the sum assured above the retention (zero at or below it). */
    static BigDecimal share(Treaty treaty, BigDecimal sumAssured) {
        if (Treaty.QUOTA_SHARE.equals(treaty.getTreatyType()) || Treaty.FACULTATIVE.equals(treaty.getTreatyType())) {
            return treaty.getCededPercent().divide(HUNDRED, 6, RoundingMode.HALF_UP);
        }
        if (sumAssured.signum() <= 0 || treaty.getRetention() == null
                || sumAssured.compareTo(treaty.getRetention()) <= 0) {
            return BigDecimal.ZERO.setScale(6);
        }
        return sumAssured.subtract(treaty.getRetention()).divide(sumAssured, 6, RoundingMode.HALF_UP);
    }

    // ── recoveries ───────────────────────────────────────────────────────────────────────────────

    /**
     * {@code approval}: the liability was approved — the expectation is created. Otherwise the claim was paid: an
     * expectation from approval is re-stated at the paid amount, or (claims approved before this existed) created now.
     */
    private void recover(DomainEvent event, boolean approval) {
        String claimRef = event.text("claimRef");
        String policyRef = event.text("policyRef");
        if (claimRef == null || policyRef == null) {
            return;
        }
        BigDecimal claimAmount = event.amount("amount").setScale(2, RoundingMode.HALF_UP);
        List<Recovery> existing = em.createQuery("select r from Recovery r join fetch r.treaty where r.claimRef = :ref",
                Recovery.class).setParameter("ref", claimRef).getResultList();
        if (!existing.isEmpty()) {
            if (!approval) {
                for (Recovery r : existing) {
                    r.claimPaid(claimAmount);
                    if (Recovery.EXPECTED.equals(r.getStatus()) && claimAmount.compareTo(r.getClaimAmount()) != 0) {
                        BigDecimal before = r.getRecoveryAmount();
                        BigDecimal proportionalShare = existing.stream()
                                .filter(x -> !Treaty.EXCESS_OF_LOSS.equals(x.getTreaty().getTreatyType()))
                                .map(Recovery::getCededShare).reduce(BigDecimal.ZERO, BigDecimal::add);
                        BigDecimal after = Treaty.EXCESS_OF_LOSS.equals(r.getTreaty().getTreatyType())
                                ? r.getTreaty().xlRecovery(claimAmount.subtract(claimAmount.multiply(proportionalShare)
                                        .setScale(2, RoundingMode.HALF_UP)))
                                : claimAmount.multiply(r.getCededShare()).setScale(2, RoundingMode.HALF_UP);
                        r.reexpect(claimAmount, after);
                        publishRecognised(r, after.subtract(before), event);
                    }
                }
            }
            return;
        }
        if (!em.createQuery("select r.id from Recovery r where r.sourceEventId = :id", Long.class)
                .setParameter("id", event.eventId()).setMaxResults(1).getResultList().isEmpty()) {
            return;
        }
        LocalDate eventDate = claimEventDate(claimRef, event.occurredAt().toLocalDate());
        Exposure exposure = em.createQuery("select e from Exposure e left join fetch e.treaty where e.policyRef = :ref"
                        + " and e.status <> :none order by e.version desc", Exposure.class)
                .setParameter("ref", policyRef).setParameter("none", Exposure.NO_TREATY).getResultList().stream()
                .filter(e -> e.inForceOn(eventDate) || (e.getEndedOn() == null && !e.getEffectiveFrom().isAfter(eventDate)))
                .findFirst().orElse(null);
        List<Object[]> shares = new ArrayList<>();
        if (exposure != null) {
            if (exposure.getTreaty() != null && exposure.getCededShare().signum() > 0) {
                shares.add(new Object[] {exposure.getTreaty(), exposure.getCededShare(), event.eventId()});
            }
            Facultative fac = exposure.getFacultative();
            if (fac != null && Facultative.ACCEPTED.equals(fac.getStatus()) && fac.getFacTreaty() != null) {
                shares.add(new Object[] {fac.getFacTreaty(), fac.getAcceptedSum().divide(exposure.getSumAssured(), 6,
                        RoundingMode.HALF_UP), derived(event.eventId(), "FAC")});
            }
        } else {
            // No exposure on record (ceded before exposures existed): the share of the policy's latest cession.
            em.createQuery("select c from Cession c join fetch c.treaty where c.policyRef = :ref and c.adjusts is null"
                            + " order by c.createdAt desc, c.id desc", Cession.class)
                    .setParameter("ref", policyRef).setMaxResults(1).getResultStream()
                    .forEach(c -> shares.add(new Object[] {c.getTreaty(), c.getCededShare(), event.eventId()}));
        }
        // NS-SH07-001: an excess-of-loss cover in force on the event date recovers the part of the claim above its
        // priority, up to its limit, alongside any proportional share.
        String product = event.text("product");
        for (Treaty xl : em.createQuery("select t from Treaty t where t.status = :approved and t.treatyType = :xl"
                        + " and t.effectiveFrom <= :day and t.effectiveTo >= :day order by t.id", Treaty.class)
                .setParameter("approved", Treaty.APPROVED).setParameter("xl", Treaty.EXCESS_OF_LOSS)
                .setParameter("day", eventDate).getResultList()) {
            String policyProduct = product != null ? product : em.createQuery("select p.product from InsurancePolicy p"
                    + " where p.ref = :ref", String.class).setParameter("ref", policyRef).getResultStream().findFirst()
                    .orElse(null);
            // On the insurer's net retained claim: the gross claim less the proportional shares recovered above.
            BigDecimal proportional = shares.stream()
                    .map(x -> claimAmount.multiply((BigDecimal) x[1]).setScale(2, RoundingMode.HALF_UP))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal recovered = xl.xlRecovery(claimAmount.subtract(proportional));
            if (xl.covers(policyProduct) && recovered.signum() > 0 && claimAmount.signum() > 0) {
                shares.add(new Object[] {xl, recovered.divide(claimAmount, 6, RoundingMode.HALF_UP),
                        derived(event.eventId(), "XL-" + xl.getRef()), recovered});
            }
        }
        for (Object[] s : shares) {
            Treaty treaty = (Treaty) s[0];
            BigDecimal share = (BigDecimal) s[1];
            BigDecimal recoveryAmount = s.length > 3 ? (BigDecimal) s[3]
                    : claimAmount.multiply(share).setScale(2, RoundingMode.HALF_UP);
            Recovery recovery = new Recovery(treaty, claimRef, policyRef, (String) s[2], claimAmount, share,
                    recoveryAmount).dated(eventDate);
            if (!approval) {
                recovery.claimPaid(claimAmount);
            }
            em.persist(recovery);
            em.flush();
            recovery.numbered();
            publishRecognised(recovery, recoveryAmount, event);
        }
    }

    private void publishRecognised(Recovery recovery, BigDecimal amount, DomainEvent cause) {
        if (amount.signum() == 0) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recoveryRef", recovery.getRef());
        data.put("treatyRef", recovery.getTreaty().getRef());
        data.put("claimRef", recovery.getClaimRef());
        data.put("policyRef", recovery.getPolicyRef());
        data.put("amount", amount.toPlainString());
        data.put("basis", Events.CLAIM_APPROVED.equals(cause.eventType()) ? "LIABILITY_APPROVED" : "CLAIM_PAID");
        outbox.publish(Events.RECOVERY_RECOGNISED, recovery.getRef(), data, ACTOR, cause.correlationId());
    }

    private LocalDate claimEventDate(String claimRef, LocalDate fallback) {
        String raw = em.createQuery("select c.eventDate from InsuranceClaim c where c.ref = :ref", String.class)
                .setParameter("ref", claimRef).getResultStream().findFirst().orElse(null);
        return date(raw, fallback);
    }

    // ── lookups ──────────────────────────────────────────────────────────────────────────────────

    /** The automatic treaty covering the product in force on {@code day}, approved first. */
    Optional<Treaty> automaticTreaty(String product, LocalDate day) {
        return em.createQuery("select t from Treaty t where t.status = :approved and t.treatyType in :proportional"
                        + " and t.effectiveFrom <= :day and t.effectiveTo >= :day order by t.approvedAt asc, t.id asc",
                        Treaty.class)
                .setParameter("approved", Treaty.APPROVED)
                .setParameter("proportional", Set.of(Treaty.QUOTA_SHARE, Treaty.SURPLUS))
                .setParameter("day", day)
                .getResultList()
                .stream()
                .filter(t -> t.covers(product))
                .findFirst();
    }

    private Exposure live(String policyRef) {
        return em.createQuery("select e from Exposure e left join fetch e.treaty where e.policyRef = :ref"
                        + " and e.status in :live order by e.version desc", Exposure.class)
                .setParameter("ref", policyRef).setParameter("live", Set.of(Exposure.ACTIVE, Exposure.NO_TREATY))
                .setMaxResults(1).getResultStream().findFirst().orElse(null);
    }

    private InsurancePolicy policy(String ref) {
        return ref == null ? null : em.createQuery("select p from InsurancePolicy p where p.ref = :ref",
                        InsurancePolicy.class)
                .setParameter("ref", ref).getResultStream().findFirst().orElse(null);
    }

    private boolean exposureExists(String eventId) {
        return !em.createQuery("select e.id from Exposure e where e.sourceEventId = :id", Long.class)
                .setParameter("id", eventId).setMaxResults(1).getResultList().isEmpty();
    }

    private boolean cessionExists(String sourceEventId) {
        return !em.createQuery("select c.id from Cession c where c.sourceEventId = :id", Long.class)
                .setParameter("id", sourceEventId).setMaxResults(1).getResultList().isEmpty();
    }

    /** A stable 36-character id for a second record made from one event (e.g. an adjustment per cession). */
    static String derived(String eventId, String part) {
        return UUID.nameUUIDFromBytes((eventId + "/" + part).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static LocalDate date(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim().length() > 10 ? raw.trim().substring(0, 10) : raw.trim());
        } catch (DateTimeParseException ex) {
            return fallback;
        }
    }
}
