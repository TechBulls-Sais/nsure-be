package com.nsure.portfolio;

import com.nsure.common.exception.NotFoundException;
import com.nsure.controls.ControlKey;
import com.nsure.controls.ControlService;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Policy lifecycle, as on the demo server (policy_management/tasks.py, api.py create_pending_policy / pay_policy,
 * nsure_integrations/payments.activate_policy):
 *
 * <pre>
 *   buy ─▶ PENDING PAYMENT ──paid──▶ ACTIVE ──grace ends unpaid (daily)──▶ ACTIVE - NO COVER ──3 months──▶ LAPSED
 *               │      └──payment window passes (hourly, or on a payment attempt)──▶ EXPIRED    │
 *               └──cancel (with a reason)──▶ CANCELLED        premium collected: cover resumes ◀─┘
 *   ACTIVE / ACTIVE - NO COVER ──written request (cooling-off flagged within 30 days)──▶ CANCELLED
 *   ACTIVE ──death claim on the principal paid, no spouse covered (TC-24)──▶ CLAIMED   (spec 10 §1.3)
 * </pre>
 *
 * A premium paid on an ACTIVE policy moves its next due date and grace period on. Only this class writes a
 * policy's status once it exists.
 */
@Service
public class PolicyWorkflow {

    public static final String PENDING_PAYMENT = "Pending Payment";
    public static final String ACTIVE = "Active";
    public static final String EXPIRED = "Expired";
    public static final String CANCELLED = "Cancelled";
    public static final String LAPSED = "Lapsed";
    /** Spec 10 W-5 / Tap Cover TC-18: in force for 3 months after cover ends, no cover, revivable without new
     *  waiting periods. */
    public static final String ACTIVE_NO_COVER = "Active - No Cover";
    /** Spec 10 W-7: terminated by a paid claim (TC-24). */
    public static final String CLAIMED = "Claimed";
    /** TC-18 / TC-20: months a policy stays in force without cover before it lapses. */
    static final int NO_COVER_MONTHS = 3;
    /** TC-23: cooling-off period after the policy start date. */
    static final int COOLING_OFF_DAYS = 30;

    /** Payment methods the demo server's checkout offers. */
    public static final List<String> PAYMENT_METHODS = List.of("Orange Money", "MyZaka", "Card", "Bank EFT");
    static final int MAX_BENEFICIARIES = 5;

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final EntityManager em;
    private final ControlService controls;
    private final WorkflowHistory history;
    private final Outbox outbox;
    private final ReferenceNumbers references;

    public PolicyWorkflow(EntityManager em, ControlService controls, WorkflowHistory history, Outbox outbox,
            ReferenceNumbers references) {
        this.references = references;
        this.em = em;
        this.controls = controls;
        this.history = history;
        this.outbox = outbox;
    }

    /** A customer's purchase: reserved, unpaid, and confers no cover until paid within the payment window. */
    void openPending(InsurancePolicy policy) {
        policy.setStatus(PENDING_PAYMENT);
        policy.setPaymentDueBy(LocalDateTime.now().plusHours(paymentWindowHours()));
    }

    /** Logged once the new policy has its id and reference. */
    void logOpened(InsurancePolicy policy, String actor) {
        history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Created", null,
                policy.getStatus(), policy.getProduct() + tierSuffix(policy) + " · premium " + bwp(policy.getPremium())
                        + (policy.getPaymentDueBy() == null ? "" : " · pay by " + policy.getPaymentDueBy().withNano(0)),
                actor);
    }

    /** Staff-created cover needs no online payment and starts active today (demo server admin_create_policy). */
    void startActive(InsurancePolicy policy) {
        policy.setStatus(ACTIVE);
        setCoverDates(policy, LocalDate.now());
    }

    /**
     * Collects a premium. On a Pending Payment policy it is the first premium and the policy goes Active with
     * cover from today; on an Active policy it pays the next period. Anything else cannot be paid.
     */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public InsurancePolicy pay(String ref, String method, String actor) {
        return pay(ref, method, actor, null);
    }

    /**
     * Collects one premium and records it as a {@link PremiumPayment} for its period; {@code transactionId} links
     * it to the provider transaction that settled it (spec 13). A period already collected is refused, and the
     * database unique key backs that up, so a premium is never taken twice for one period (BR-66).
     */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public InsurancePolicy pay(String ref, String method, String actor, Long transactionId) {
        InsurancePolicy policy = policy(ref);
        em.lock(policy, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        String paymentMethod = paymentMethod(method);
        if (expireIfOverdue(policy)) {
            throw new IllegalArgumentException("Policy " + ref + " is " + EXPIRED
                    + ": its payment window has passed and it can no longer be paid. Please buy a new policy.");
        }
        String previous = policy.getStatus();
        LocalDate today = LocalDate.now();
        Period period = nextPeriod(policy);
        assertPeriodOpen(policy, period);
        String details;
        switch (previous) {
            case PENDING_PAYMENT -> {
                setCoverDates(policy, today);
                policy.setStatus(ACTIVE);
                policy.setPaymentDueBy(null);
                policy.setRecordDate(today.format(DISPLAY));
                CustomerAccount account = account(policy.getCustomerName());
                account.setPolicyCount(account.getPolicyCount() + 1);
                account.setProduct(policy.getProduct());
                em.createQuery("select p from InsuranceProduct p where p.name = :name", InsuranceProduct.class)
                        .setParameter("name", policy.getProduct())
                        .getResultStream()
                        .findFirst()
                        .ifPresent(product -> product.setLiveCount(product.getLiveCount() + 1));
                details = "First premium " + bwp(policy.getPremium()) + " by " + paymentMethod
                        + " · cover from " + policy.getCoverStartDate() + ", next due " + policy.getNextPremiumDue();
            }
            case ACTIVE -> {
                LocalDate from = policy.getNextPremiumDue() == null ? today : policy.getNextPremiumDue();
                LocalDate next = from.plusMonths(months(policy));
                policy.setNextPremiumDue(next);
                policy.setGracePeriodEnd(next.plusDays(product(policy).getGracePeriodDays()));
                details = "Premium " + bwp(policy.getPremium()) + " by " + paymentMethod + " · next due " + next;
            }
            case ACTIVE_NO_COVER -> {
                // TC-18: cover resumes immediately; the original cover start stands, so no new waiting period.
                LocalDate next = today.plusMonths(months(policy));
                policy.setStatus(ACTIVE);
                policy.setNextPremiumDue(next);
                policy.setGracePeriodEnd(next.plusDays(product(policy).getGracePeriodDays()));
                details = "Premium " + bwp(policy.getPremium()) + " by " + paymentMethod
                        + " · cover resumed from " + today + " with no new waiting period · next due " + next;
            }
            default -> throw new IllegalArgumentException(
                    "Policy " + ref + " is " + previous + " and can no longer be paid. Please buy a new policy.");
        }
        policy.setPaymentMethod(paymentMethod);
        String receiptRef = receipt(policy, paymentMethod);
        em.persist(new PremiumPayment(policy, period.start(), period.end(), paymentMethod, receiptRef, transactionId,
                actor));
        boolean first = PENDING_PAYMENT.equals(previous);
        history.record(WorkflowHistory.POLICY, ref, policy.getCustomerName(),
                first ? "Payment Received" : "Premium Received",
                previous, policy.getStatus(), details, actor);
        // Downstream effects (subledger, commission, reinsurance, messages) follow from these events, after commit.
        if (first) {
            Map<String, Object> issued = new LinkedHashMap<>();
            issued.put("policyRef", ref);
            issued.put("customerName", policy.getCustomerName());
            issued.put("product", policy.getProduct());
            issued.put("sumAssured", policy.getAmount());
            issued.put("premium", policy.getPremium().toPlainString());
            outbox.publish(Events.POLICY_ISSUED, ref, issued, actor);
        }
        Map<String, Object> premium = new LinkedHashMap<>();
        premium.put("policyRef", ref);
        premium.put("customerName", policy.getCustomerName());
        premium.put("product", policy.getProduct());
        premium.put("amount", policy.getPremium().toPlainString());
        premium.put("method", paymentMethod);
        premium.put("receiptRef", receiptRef);
        premium.put("firstPremium", first);
        outbox.publish(Events.PREMIUM_RECEIVED, ref, premium, actor);
        return policy;
    }

    /** The premium period a payment would cover now, and why (spec 13 §3: FIRST_PREMIUM | RENEWAL | REINSTATEMENT). */
    public record Period(LocalDate start, LocalDate end, String purpose) {
    }

    /** The period the next premium pays for. Refuses a policy that can no longer be paid. */
    public Period nextPeriod(InsurancePolicy policy) {
        LocalDate today = LocalDate.now();
        int months = months(policy);
        return switch (policy.getStatus()) {
            case PENDING_PAYMENT -> new Period(today, today.plusMonths(months).minusDays(1), "FIRST_PREMIUM");
            case ACTIVE -> {
                LocalDate start = policy.getNextPremiumDue() == null ? today : policy.getNextPremiumDue();
                yield new Period(start, start.plusMonths(months).minusDays(1), "RENEWAL");
            }
            case ACTIVE_NO_COVER -> new Period(today, today.plusMonths(months).minusDays(1), "REINSTATEMENT");
            default -> throw new IllegalArgumentException("Policy " + policy.getRef() + " is " + policy.getStatus()
                    + " and can no longer be paid. Please buy a new policy.");
        };
    }

    /**
     * Before a payment is taken: expires an overdue pending policy (BR-55, inline on any payment attempt) and refuses
     * one that cannot be paid. Returns the period the payment will cover.
     */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public Period assertPayable(InsurancePolicy policy) {
        if (expireIfOverdue(policy)) {
            throw new IllegalArgumentException("Policy " + policy.getRef() + " is " + EXPIRED
                    + ": its payment window has passed and it can no longer be paid. Please buy a new policy.");
        }
        Period period = nextPeriod(policy);
        assertPeriodOpen(policy, period);
        return period;
    }

    private void assertPeriodOpen(InsurancePolicy policy, Period period) {
        Long collected = em.createQuery("select count(p) from PremiumPayment p where p.policyId = :policy"
                        + " and p.periodStart = :start and p.status = 'SUCCESSFUL'", Long.class)
                .setParameter("policy", policy.getId())
                .setParameter("start", period.start())
                .getSingleResult();
        if (collected > 0) {
            throw new com.nsure.common.exception.ConflictException("The premium for the period from " + period.start()
                    + " on " + policy.getRef() + " has already been collected.");
        }
    }

    /**
     * Cancels a policy (spec 10 W-7 "* → CANCELLED"): an unpaid one, or an in-force one on the policyholder's written
     * request. Within 30 days of the policy start it is a cooling-off cancellation (TC-23), which the history flags
     * so the premiums can be refunded.
     */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public InsurancePolicy cancel(String ref, String reason, String actor) {
        InsurancePolicy policy = policy(ref);
        String note = reason == null ? "" : reason.trim();
        if (note.isEmpty()) {
            throw new IllegalArgumentException("Give a reason for cancelling the policy. The customer is told why.");
        }
        if (note.length() > 500) {
            throw new IllegalArgumentException("Keep the reason to 500 characters.");
        }
        expireIfOverdue(policy);
        String previous = policy.getStatus();
        if (!Set.of(PENDING_PAYMENT, ACTIVE, ACTIVE_NO_COVER).contains(previous)) {
            throw new IllegalArgumentException("Policy " + ref + " is " + previous + " and can no longer be cancelled.");
        }
        boolean coolingOff = !PENDING_PAYMENT.equals(previous) && policy.getCoverStartDate() != null
                && !LocalDate.now().isAfter(policy.getCoverStartDate().plusDays(COOLING_OFF_DAYS));
        policy.setStatus(CANCELLED);
        policy.setPaymentDueBy(null);
        policy.review(actor, note);
        history.record(WorkflowHistory.POLICY, ref, policy.getCustomerName(), "Cancelled", previous,
                CANCELLED, PENDING_PAYMENT.equals(previous) ? note
                        : (coolingOff ? "Cooling-off cancellation within " + COOLING_OFF_DAYS
                                + " days of the policy start: premiums refundable unless an insured event occurred · "
                                : "Cancelled on the policyholder's written request · ") + note, actor);
        outbox.publish(Events.POLICY_CANCELLED, ref, Map.of("policyRef", ref, "customerName",
                policy.getCustomerName(), "product", policy.getProduct(), "reason", note), actor);
        return policy;
    }

    /** Pending Payment past its payment window → Expired. Runs hourly; a payment attempt also runs it. */
    @Transactional
    public List<String> expireUnpaid() {
        List<InsurancePolicy> overdue = em.createQuery(
                        "select p from InsurancePolicy p where p.status = :status and p.paymentDueBy is not null"
                                + " and p.paymentDueBy < :now", InsurancePolicy.class)
                .setParameter("status", PENDING_PAYMENT)
                .setParameter("now", LocalDateTime.now())
                .getResultList();
        overdue.forEach(this::expireIfOverdue);
        return overdue.stream().map(InsurancePolicy::getRef).toList();
    }

    /**
     * Daily sweep (spec 10 §7): Active with the grace period over and no premium → Active - No Cover (TC-18); then
     * Active - No Cover for more than three months after cover ended → Lapsed (TC-20). Lapse is terminal: revival is
     * a new policy. Returns the policies that moved.
     */
    @Transactional
    public List<String> lapseOverdue() {
        LocalDate today = LocalDate.now();
        List<InsurancePolicy> overdue = em.createQuery(
                        "select p from InsurancePolicy p where p.status = :status and p.gracePeriodEnd is not null"
                                + " and p.gracePeriodEnd < :today", InsurancePolicy.class)
                .setParameter("status", ACTIVE)
                .setParameter("today", today)
                .getResultList();
        List<String> moved = new java.util.ArrayList<>();
        for (InsurancePolicy policy : overdue) {
            policy.setStatus(ACTIVE_NO_COVER);
            history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Cover Ended", ACTIVE,
                    ACTIVE_NO_COVER, "Grace period ended " + policy.getGracePeriodEnd() + " with the premium due "
                            + policy.getNextPremiumDue() + " unpaid. The policy stays in force without cover for "
                            + NO_COVER_MONTHS + " months; paying resumes cover with no new waiting period.",
                    WorkflowHistory.SYSTEM);
            moved.add(policy.getRef());
        }
        List<InsurancePolicy> noCover = em.createQuery(
                        "select p from InsurancePolicy p where p.status = :status", InsurancePolicy.class)
                .setParameter("status", ACTIVE_NO_COVER)
                .getResultList();
        for (InsurancePolicy policy : noCover) {
            LocalDate coverEnd = policy.getNextPremiumDue() != null ? policy.getNextPremiumDue() : policy.getGracePeriodEnd();
            if (coverEnd == null || !coverEnd.plusMonths(NO_COVER_MONTHS).isBefore(today)) {
                continue;
            }
            policy.setStatus(LAPSED);
            policy.setLapseDate(today);
            history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Lapsed", ACTIVE_NO_COVER,
                    LAPSED, "No premium within " + NO_COVER_MONTHS + " months of cover ending on " + coverEnd
                            + ". Lapse is final: re-enrolling starts a new policy with new waiting periods.",
                    WorkflowHistory.SYSTEM);
            moved.add(policy.getRef());
        }
        return moved;
    }

    /**
     * Spec 10 W-7 / TC-24: a paid death claim on the principal ends the policy unless a spouse is covered (then it
     * survives and ownership transfers). A claim for the full sum assured is taken as the principal's death.
     */
    void terminateIfClaimEndsCover(InsurancePolicy policy, InsuranceClaim claim, String actor) {
        if (!Set.of(ACTIVE, ACTIVE_NO_COVER).contains(policy.getStatus())
                || claim.getPayoutAmount() == null || claim.getPayoutAmount() < policy.getAmount()) {
            return;
        }
        boolean spouseCovered = em.createQuery("select count(l) from PolicyLifeCover l where l.policy = :policy"
                        + " and lower(l.lifeType) = 'spouse'", Long.class)
                .setParameter("policy", policy)
                .getSingleResult() > 0;
        if (spouseCovered) {
            history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Claim Paid",
                    policy.getStatus(), policy.getStatus(), "Claim " + claim.getRef()
                            + " paid; a spouse is covered, so the policy continues (TC-25)", actor);
            return;
        }
        String previous = policy.getStatus();
        policy.setStatus(CLAIMED);
        history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Claimed", previous, CLAIMED,
                "Ended by claim " + claim.getRef() + " (TC-24)", actor);
    }

    boolean expireIfOverdue(InsurancePolicy policy) {
        if (!PENDING_PAYMENT.equals(policy.getStatus()) || policy.getPaymentDueBy() == null
                || !policy.getPaymentDueBy().isBefore(LocalDateTime.now())) {
            return false;
        }
        policy.setStatus(EXPIRED);
        history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Expired", PENDING_PAYMENT,
                EXPIRED, "Not paid by " + policy.getPaymentDueBy().withNano(0), WorkflowHistory.SYSTEM);
        return true;
    }

    // ── beneficiaries ─────────────────────────────────────────────────────────────────────────────

    @Transactional
    public PolicyBeneficiary addBeneficiary(String ref, BeneficiaryInput input, String actor) {
        InsurancePolicy policy = policy(ref);
        if (Set.of(CANCELLED, EXPIRED, LAPSED).contains(policy.getStatus())) {
            throw new IllegalArgumentException("Policy " + ref + " is " + policy.getStatus()
                    + "; its beneficiaries can no longer be changed.");
        }
        List<PolicyBeneficiary> existing = beneficiaries(policy);
        int max = product(policy).getMaxBeneficiaries();
        if (existing.size() >= max) {
            throw new IllegalArgumentException("A " + policy.getProduct() + " policy can have at most " + max
                    + (max == 1 ? " beneficiary." : " beneficiaries."));
        }
        PolicyBeneficiary row = new PolicyBeneficiary(policy,
                existing.stream().mapToInt(PolicyBeneficiary::getDisplayOrder).max().orElse(0) + 1);
        input.applyTo(row);
        if (row.isPrimary()) {
            existing.forEach(other -> other.setPrimary(false));
        } else if (existing.isEmpty()) {
            row.setPrimary(true);
        }
        em.persist(row);
        history.record(WorkflowHistory.POLICY, ref, policy.getCustomerName(), "Beneficiary Added",
                policy.getStatus(), policy.getStatus(), row.getFullName() + " (" + row.getRelationship() + ")", actor);
        return row;
    }

    @Transactional
    public void removeBeneficiary(String ref, Long beneficiaryId, String actor) {
        InsurancePolicy policy = policy(ref);
        PolicyBeneficiary row = beneficiaries(policy).stream()
                .filter(item -> item.getId().equals(beneficiaryId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Beneficiary " + beneficiaryId + " is not on " + ref));
        em.remove(row);
        if (row.isPrimary()) {
            beneficiaries(policy).stream().filter(item -> !item.getId().equals(beneficiaryId)).findFirst()
                    .ifPresent(next -> next.setPrimary(true));
        }
        history.record(WorkflowHistory.POLICY, ref, policy.getCustomerName(), "Beneficiary Removed",
                policy.getStatus(), policy.getStatus(), row.getFullName(), actor);
    }

    List<PolicyBeneficiary> beneficiaries(InsurancePolicy policy) {
        return em.createQuery(
                        "select b from PolicyBeneficiary b where b.policy = :policy order by b.displayOrder, b.id",
                        PolicyBeneficiary.class)
                .setParameter("policy", policy)
                .getResultList();
    }

    /** A beneficiary as sent by a form; validated before anything is stored. */
    public record BeneficiaryInput(String fullName, String relationship, String nationalId, String mobile,
            String payoutMethod, String accountRef, boolean primary) {

        void applyTo(PolicyBeneficiary row) {
            String name = trim(fullName);
            String related = trim(relationship);
            if (name.isEmpty() || related.isEmpty()) {
                throw new IllegalArgumentException("A beneficiary needs a full name and a relationship.");
            }
            if (name.length() > 200 || related.length() > 80) {
                throw new IllegalArgumentException("Beneficiary name or relationship is too long.");
            }
            String method = trim(payoutMethod);
            String account = trim(accountRef);
            if (method.isEmpty() != account.isEmpty()) {
                throw new IllegalArgumentException("Give both a payout method and an account / wallet number, or neither.");
            }
            row.setFullName(name);
            row.setRelationship(related);
            row.setNationalId(limit(trim(nationalId), 40));
            row.setMobile(limit(trim(mobile), 40));
            row.setPayoutMethod(limit(method, 80));
            row.setAccountRef(limit(account, 80));
            row.setPrimary(primary);
        }

        private static String trim(String value) {
            return value == null ? "" : value.trim();
        }

        private static String limit(String value, int max) {
            if (value.isEmpty()) {
                return null;
            }
            if (value.length() > max) {
                throw new IllegalArgumentException("Beneficiary details are too long.");
            }
            return value;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    public InsurancePolicy policy(String ref) {
        return em.createQuery("select p from InsurancePolicy p where p.ref = :ref", InsurancePolicy.class)
                .setParameter("ref", ref)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Policy " + ref + " not found"));
    }

    InsuranceProduct product(InsurancePolicy policy) {
        return em.createQuery("select p from InsuranceProduct p where p.name = :name", InsuranceProduct.class)
                .setParameter("name", policy.getProduct())
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Product " + policy.getProduct() + " not found"));
    }

    /** Cover from `start`; next premium one premium period later; lapse after the product's grace days. */
    private void setCoverDates(InsurancePolicy policy, LocalDate start) {
        int months = months(policy);
        LocalDate next = months > 0 ? start.plusMonths(months) : start.plusYears(1);
        policy.setCoverStartDate(start);
        policy.setNextPremiumDue(next);
        policy.setGracePeriodEnd(next.plusDays(product(policy).getGracePeriodDays()));
        policy.setLapseDate(null);
    }

    /** Months one premium pays for, from the tier's premium frequency; monthly when the policy has no tier. */
    int months(InsurancePolicy policy) {
        String frequency = policy.getTier() == null ? null : em.createQuery(
                        "select t.premiumFrequency from CoverTier t where t.product.name = :product and t.name = :tier",
                        String.class)
                .setParameter("product", policy.getProduct())
                .setParameter("tier", policy.getTier())
                .getResultStream()
                .findFirst()
                .orElse(null);
        if (frequency == null) {
            return 1;
        }
        return em.createQuery(
                        "select f.months from PremiumFrequency f where f.frequencyName = :name", Integer.class)
                .setParameter("name", frequency)
                .getResultStream()
                .findFirst()
                .orElse(1);
    }

    private long paymentWindowHours() {
        return controls.valueOf(ControlKey.POLICY_PAYMENT_WINDOW_HOURS.key()).longValue();
    }

    private String receipt(InsurancePolicy policy, String method) {
        PremiumReceipt row = new PremiumReceipt();
        row.setRef(references.next("INV-", 4, () -> references.highest("PremiumReceipt", "INV-")));
        row.setCustomerName(policy.getCustomerName());
        row.setCustomerRef(policy.getCustomerRef());
        row.setProduct(policy.getProduct());
        row.setAmount(policy.getPremium().setScale(0, RoundingMode.UP).intValue());
        row.setPremium(BigDecimal.ZERO);
        row.setStatus("Paid");
        row.setRecordDate(LocalDate.now().format(DISPLAY));
        row.setDisplayOrder(0);
        em.persist(row);
        return row.getRef();
    }

    private CustomerAccount account(String customerName) {
        return em.createQuery(
                        "select c from CustomerAccount c where c.name = :name order by c.displayOrder asc",
                        CustomerAccount.class)
                .setParameter("name", customerName)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Customer " + customerName + " not found"));
    }

    static String paymentMethod(String method) {
        String value = method == null ? "" : method.trim();
        if (!PAYMENT_METHODS.contains(value)) {
            throw new IllegalArgumentException("Payment method must be one of " + String.join(", ", PAYMENT_METHODS) + ".");
        }
        return value;
    }

    private static String tierSuffix(InsurancePolicy policy) {
        return policy.getTier() == null ? "" : " " + policy.getTier();
    }

    static String bwp(BigDecimal amount) {
        return String.format(Locale.ENGLISH, "BWP %,.2f", amount);
    }
}
