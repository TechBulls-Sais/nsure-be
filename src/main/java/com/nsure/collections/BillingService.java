package com.nsure.collections;

import com.nsure.integration.Events;
import com.nsure.integration.InboundMessage;
import com.nsure.integration.Outbox;
import com.nsure.payment.PaymentTransaction;
import com.nsure.portfolio.InsurancePolicy;
import com.nsure.portfolio.PremiumPayment;
import com.nsure.portfolio.ReferenceNumbers;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The due schedule and what has been applied to it (NS-SH04-001/003/004). Each premium period is an instalment with
 * a fixed billed amount; collections, partial suspense applications and corrections are separate records against it.
 * Paid-to and arrears are always worked out from those records.
 */
@Service
public class BillingService {

    private static final Set<String> OPEN_TRANSACTION = Set.of(PaymentTransaction.CREATED,
            PaymentTransaction.REDIRECTED, PaymentTransaction.PENDING_VERIFICATION);

    private final EntityManager em;
    private final Outbox outbox;
    private final ReferenceNumbers references;

    public BillingService(EntityManager em, Outbox outbox, ReferenceNumbers references) {
        this.em = em;
        this.outbox = outbox;
        this.references = references;
    }

    // ── schedule ─────────────────────────────────────────────────────────────────────────────────

    /** The policy's instalment for the period starting {@code start}, issued (InvoiceIssued) the first time. */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = IllegalArgumentException.class)
    public Instalment instalmentFor(InsurancePolicy policy, LocalDate start, LocalDate end, String purpose,
            String actor) {
        Instalment existing = find(policy.getRef(), start);
        if (existing != null) {
            return existing;
        }
        BigDecimal billed = policy.getPremium() == null ? BigDecimal.ZERO
                : policy.getPremium().setScale(2, RoundingMode.HALF_UP);
        Instalment instalment = new Instalment(references.next("BIL-", 6, () -> 0L), policy.getRef(),
                policy.getCustomerRef(), start, end, billed, purpose, actor == null ? "system" : actor);
        em.persist(instalment);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("instalmentRef", instalment.getRef());
        data.put("policyRef", policy.getRef());
        data.put("periodStart", start.toString());
        data.put("periodEnd", end.toString());
        data.put("dueDate", instalment.getDueDate().toString());
        data.put("amount", billed.toPlainString());
        outbox.publish(Events.INVOICE_ISSUED, instalment.getRef(), data, actor);
        return instalment;
    }

    /**
     * A full premium collected for a period: what is still outstanding on its instalment is applied from the receipt,
     * which settles it. Called by the policy workflow for every collection.
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = IllegalArgumentException.class)
    public Allocation collect(InsurancePolicy policy, LocalDate start, LocalDate end, String purpose,
            String receiptRef, String actor) {
        Instalment instalment = instalmentFor(policy, start, end, purpose, actor);
        BigDecimal outstanding = outstanding(instalment);
        if (outstanding.signum() <= 0) {
            throw new IllegalArgumentException("Instalment " + instalment.getRef() + " is already settled; nothing is due.");
        }
        return allocate(instalment, Allocation.RECEIPT, receiptRef, outstanding, actor);
    }

    /** Part of a suspense receipt applied to an instalment that it does not settle (NS-SH04-003). */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = IllegalArgumentException.class)
    public Allocation applyPart(InsurancePolicy policy, LocalDate start, LocalDate end, String purpose,
            String suspenseRef, BigDecimal amount, String actor) {
        Instalment instalment = instalmentFor(policy, start, end, purpose, actor);
        BigDecimal outstanding = outstanding(instalment);
        if (amount.signum() <= 0 || amount.compareTo(outstanding) >= 0) {
            throw new IllegalArgumentException("A part payment must be more than zero and less than the BWP "
                    + outstanding.toPlainString() + " outstanding on " + instalment.getRef() + ".");
        }
        return allocate(instalment, Allocation.SUSPENSE, suspenseRef, amount, actor);
    }

    /** What the policy's next period still needs: its instalment's outstanding amount, or the premium if not issued. */
    public BigDecimal outstandingFor(InsurancePolicy policy, LocalDate start) {
        Instalment instalment = find(policy.getRef(), start);
        if (instalment == null) {
            return policy.getPremium() == null ? BigDecimal.ZERO : policy.getPremium().setScale(2, RoundingMode.HALF_UP);
        }
        return outstanding(instalment);
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = IllegalArgumentException.class)
    public Adjustment adjust(String instalmentRef, BigDecimal amount, String reason, String actor) {
        Instalment instalment = byRef(instalmentRef);
        BigDecimal after = outstanding(instalment).add(amount);
        if (after.signum() < 0) {
            throw new IllegalArgumentException("That would leave BWP " + after.toPlainString() + " owed on "
                    + instalmentRef + "; an adjustment cannot make an instalment owe less than nothing.");
        }
        Adjustment adjustment = new Adjustment(references.next("ADJ-", 6, () -> 0L), instalment, amount, reason, actor);
        em.persist(adjustment);
        em.flush();
        restatus(instalment);
        return adjustment;
    }

    /** Reverses every live allocation made from {@code receiptRef}; returns the instalments it reopened. */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = IllegalArgumentException.class)
    public List<Instalment> reverseAllocations(String receiptRef, String reversalRef, String actor) {
        List<Instalment> touched = new ArrayList<>();
        for (Allocation allocation : em.createQuery("select a from Allocation a where a.sourceType = :type"
                        + " and a.sourceRef = :ref and a.reversedAt is null", Allocation.class)
                .setParameter("type", Allocation.RECEIPT).setParameter("ref", receiptRef).getResultList()) {
            allocation.reverse(reversalRef, actor);
            touched.add(allocation.getInstalment());
        }
        em.flush();
        touched.forEach(this::restatus);
        return touched;
    }

    /** The start of the earliest instalment still owed, or null when every issued instalment is settled or waived. */
    public LocalDate firstUnsettledStart(String policyRef) {
        return em.createQuery("select min(i.periodStart) from Instalment i where i.policyRef = :ref"
                        + " and i.status not in :closed", LocalDate.class)
                .setParameter("ref", policyRef).setParameter("closed", Set.of(Instalment.PAID, Instalment.WAIVED))
                .getSingleResult();
    }

    /**
     * Cover resumed without back premiums (TC-18): instalments for the months without cover are not owed. Each is
     * waived by an adjustment that says why, so its billed amount and history stay.
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = IllegalArgumentException.class)
    public void waiveBefore(String policyRef, LocalDate resumedOn, String actor) {
        for (Instalment i : em.createQuery("select i from Instalment i where i.policyRef = :ref"
                        + " and i.periodStart < :from and i.status in :open", Instalment.class)
                .setParameter("ref", policyRef).setParameter("from", resumedOn)
                .setParameter("open", Set.of(Instalment.DUE, Instalment.PART_PAID)).getResultList()) {
            BigDecimal outstanding = outstanding(i);
            if (outstanding.signum() > 0) {
                em.persist(new Adjustment(references.next("ADJ-", 6, () -> 0L), i, outstanding.negate(),
                        "Not owed: no cover in this period; cover resumed on " + resumedOn, actor));
                em.flush();
            }
            i.status(Instalment.WAIVED);
        }
    }

    /**
     * Money that may still arrive or be applied for this policy (NS-SH04-004): an open provider transaction, or a
     * receipt in suspense that names it. Grace and lapse decisions wait while any of these is unresolved.
     */
    public List<String> unresolvedPayments(String policyRef) {
        List<String> found = new ArrayList<>();
        em.createQuery("select t from PaymentTransaction t where t.policyRef = :ref and t.status in :open"
                        + " and t.expiresAt > :now", PaymentTransaction.class)
                .setParameter("ref", policyRef).setParameter("open", OPEN_TRANSACTION)
                .setParameter("now", LocalDateTime.now())
                .getResultStream()
                .forEach(t -> found.add("Payment " + t.getProviderReference() + " is " + t.getStatus().toLowerCase()));
        em.createQuery("select m from InboundMessage m where m.status = :suspense and (m.businessRef = :ref"
                        + " or m.clearancePolicyRef = :ref or m.clearanceAllocations like :mention)", InboundMessage.class)
                .setParameter("suspense", InboundMessage.SUSPENSE).setParameter("ref", policyRef)
                .setParameter("mention", "%\"" + policyRef + "\"%")
                .getResultStream()
                .forEach(m -> found.add("Receipt " + m.getProviderRef() + " (BWP " + m.getAmount().toPlainString()
                        + ") is in suspense"));
        em.createQuery("select c from CollectionInstruction c where c.policyRef = :ref and c.status in :waiting",
                        CollectionInstruction.class)
                .setParameter("ref", policyRef).setParameter("waiting", CollectionInstruction.AWAITING_BANK)
                .getResultStream()
                .forEach(c -> found.add("Collection " + c.getRef() + " (BWP " + c.getAmount().toPlainString()
                        + ") is " + c.getStatus().toLowerCase() + " at the bank"));
        return found;
    }

    // ── the account ──────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CollectionsViews.AccountView account(InsurancePolicy policy) {
        List<Instalment> instalments = em.createQuery("select i from Instalment i where i.policyRef = :ref"
                + " order by i.periodStart", Instalment.class).setParameter("ref", policy.getRef()).getResultList();
        LocalDate today = LocalDate.now();
        LocalDate paidTo = null;
        boolean contiguous = true;
        BigDecimal arrears = BigDecimal.ZERO;
        int overdue = 0;
        List<CollectionsViews.InstalmentView> views = new ArrayList<>();
        for (Instalment i : instalments) {
            BigDecimal adjustments = adjustments(i);
            BigDecimal allocated = allocated(i);
            BigDecimal outstanding = i.getBilledAmount().add(adjustments).subtract(allocated);
            if (contiguous && Instalment.PAID.equals(i.getStatus())) {
                paidTo = i.getPeriodEnd();
            } else if (!Instalment.WAIVED.equals(i.getStatus())) {
                contiguous = false;
            }
            if (outstanding.signum() > 0 && i.getDueDate().isBefore(today)) {
                arrears = arrears.add(outstanding);
                overdue++;
            }
            List<CollectionsViews.AllocationView> allocations = em.createQuery("select a from Allocation a"
                            + " where a.instalment = :i order by a.id", Allocation.class).setParameter("i", i)
                    .getResultStream()
                    .map(a -> new CollectionsViews.AllocationView(a.getRef(), a.getSourceType(), a.getSourceRef(),
                            a.getAmount(), a.getAllocatedBy(), a.getAllocatedAt(), a.getReversalRef()))
                    .toList();
            views.add(new CollectionsViews.InstalmentView(i.getRef(), i.getPeriodStart(), i.getPeriodEnd(),
                    i.getDueDate(), i.getPurpose(), i.getBilledAmount(), adjustments, allocated, outstanding,
                    i.getStatus(), allocations, i.getRowVersion()));
        }
        List<CollectionsViews.ReceiptView> receipts = em.createQuery("select p from PremiumPayment p"
                        + " where p.policyRef = :ref order by p.paidAt desc, p.id desc", PremiumPayment.class)
                .setParameter("ref", policy.getRef())
                .getResultStream()
                .map(p -> {
                    ReceiptReversal r = reversal(p.getReceiptRef());
                    return new CollectionsViews.ReceiptView(p.getReceiptRef(), p.getPeriodStart(), p.getPeriodEnd(),
                            p.getAmount(), p.getPaymentMethod(), p.getStatus(), p.getPaidAt(),
                            r == null ? null : r.getRef(), r == null ? null : r.getKind(),
                            r == null ? null : r.getReason());
                })
                .toList();
        CollectionsViews.MandateView mandate = em.createQuery("select m from CollectionMandate m"
                        + " where m.activePolicyRef = :ref", CollectionMandate.class)
                .setParameter("ref", policy.getRef()).getResultStream().findFirst().map(CollectionRuns::view)
                .orElse(null);
        List<CollectionsViews.InstructionView> instructions = em.createQuery("select c from CollectionInstruction c"
                        + " join fetch c.batch where c.policyRef = :ref order by c.id desc", CollectionInstruction.class)
                .setParameter("ref", policy.getRef()).setMaxResults(50).getResultStream().map(CollectionRuns::view)
                .toList();
        return new CollectionsViews.AccountView(policy.getRef(), policy.getStatus(), paidTo,
                policy.getNextPremiumDue(), arrears, overdue, unresolvedPayments(policy.getRef()), views, receipts,
                mandate, instructions);
    }

    ReceiptReversal reversal(String receiptRef) {
        if (receiptRef == null) {
            return null;
        }
        return em.createQuery("select r from ReceiptReversal r where r.receiptRef = :ref", ReceiptReversal.class)
                .setParameter("ref", receiptRef).getResultStream().findFirst().orElse(null);
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    private Allocation allocate(Instalment instalment, String sourceType, String sourceRef, BigDecimal amount,
            String actor) {
        Allocation allocation = new Allocation(references.next("ALC-", 6, () -> 0L), instalment, sourceType,
                sourceRef, amount.setScale(2, RoundingMode.HALF_UP), actor == null ? "system" : actor);
        em.persist(allocation);
        em.flush();
        restatus(instalment);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("allocationRef", allocation.getRef());
        data.put("instalmentRef", instalment.getRef());
        data.put("policyRef", instalment.getPolicyRef());
        data.put("sourceType", sourceType);
        data.put("sourceRef", sourceRef);
        data.put("amount", allocation.getAmount().toPlainString());
        data.put("instalmentStatus", instalment.getStatus());
        outbox.publish(Events.RECEIPT_ALLOCATED, instalment.getRef(), data, actor);
        return allocation;
    }

    private void restatus(Instalment instalment) {
        BigDecimal allocated = allocated(instalment);
        BigDecimal outstanding = instalment.getBilledAmount().add(adjustments(instalment)).subtract(allocated);
        if (Instalment.WAIVED.equals(instalment.getStatus())) {
            return;
        }
        instalment.status(outstanding.signum() <= 0 ? Instalment.PAID
                : allocated.signum() > 0 ? Instalment.PART_PAID : Instalment.DUE);
    }

    /** What is still owed on an instalment: billed plus adjustments less live allocations. */
    BigDecimal outstandingOf(Instalment instalment) {
        return outstanding(instalment);
    }

    private BigDecimal outstanding(Instalment instalment) {
        return instalment.getBilledAmount().add(adjustments(instalment)).subtract(allocated(instalment));
    }

    private BigDecimal allocated(Instalment instalment) {
        BigDecimal sum = em.createQuery("select sum(a.amount) from Allocation a where a.instalment = :i"
                + " and a.reversedAt is null", BigDecimal.class).setParameter("i", instalment).getSingleResult();
        return sum == null ? BigDecimal.ZERO.setScale(2) : sum;
    }

    private BigDecimal adjustments(Instalment instalment) {
        BigDecimal sum = em.createQuery("select sum(a.amount) from Adjustment a where a.instalment = :i",
                BigDecimal.class).setParameter("i", instalment).getSingleResult();
        return sum == null ? BigDecimal.ZERO.setScale(2) : sum;
    }

    private Instalment find(String policyRef, LocalDate start) {
        return em.createQuery("select i from Instalment i where i.policyRef = :ref and i.periodStart = :start",
                        Instalment.class)
                .setParameter("ref", policyRef).setParameter("start", start)
                .getResultStream().findFirst().orElse(null);
    }

    Instalment byRef(String ref) {
        return em.createQuery("select i from Instalment i where i.ref = :ref", Instalment.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new com.nsure.common.exception.NotFoundException("Instalment " + ref + " not found"));
    }
}
