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
    /** A premium arrived while cover was off, on a product whose rules need the reinstatement approved (NS-IL03-004). */
    public static final String REINSTATEMENT_PENDING = "Reinstatement Pending";
    public static final String TERMINATED = "Terminated";
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
    private final com.nsure.collections.BillingService billing;

    private final ProductTerms productTerms;

    public PolicyWorkflow(EntityManager em, ControlService controls, WorkflowHistory history, Outbox outbox,
            ReferenceNumbers references, com.nsure.collections.BillingService billing, ProductTerms productTerms) {
        this.productTerms = productTerms;
        this.billing = billing;
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
        // The terms it is sold under today stay with it for life (NS-SH02-004, NS-IL03-001).
        productTerms.pin(policy);
    }

    /** Logged once the new policy has its id and reference. */
    void logOpened(InsurancePolicy policy, String actor) {
        history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Created", null,
                policy.getStatus(), policy.getProduct() + tierSuffix(policy) + " · premium " + bwp(policy.getPremium())
                        // NS-SH03-003: a premium entered without a plan was not calculated; the trail says so.
                        + (policy.getTier() == null || policy.getTier().isBlank()
                                ? " · manual premium, not calculated (no plan)" : "")
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
        boolean reinstatementHeld = false;
        switch (previous) {
            case PENDING_PAYMENT -> {
                setCoverDates(policy, today);
                policy.setStatus(ACTIVE);
                policy.setPaymentDueBy(null);
                policy.setRecordDate(today.format(DISPLAY));
                CustomerAccount account = account(policy);
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
                policy.setGracePeriodEnd(next.plusDays(terms(policy).getGracePeriodDays()));
                details = "Premium " + bwp(policy.getPremium()) + " by " + paymentMethod + " · next due " + next;
            }
            case ACTIVE_NO_COVER -> {
                // TC-18: cover resumes immediately; the original cover start stands, so no new waiting period.
                billing.waiveBefore(policy.getRef(), today, actor);
                LocalDate next = today.plusMonths(months(policy));
                // NS-IL03-004: a late premium restores cover by itself only where the product's rules allow it.
                reinstatementHeld = reinstatementNeedsApproval(policy.getProduct());
                policy.setStatus(reinstatementHeld ? REINSTATEMENT_PENDING : ACTIVE);
                policy.setNextPremiumDue(next);
                policy.setGracePeriodEnd(next.plusDays(terms(policy).getGracePeriodDays()));
                details = "Premium " + bwp(policy.getPremium()) + " by " + paymentMethod + (reinstatementHeld
                        ? " · received while cover was off: reinstatement awaits approval · next due " + next
                        : " · cover resumed from " + today + " with no new waiting period · next due " + next);
            }
            default -> throw new IllegalArgumentException(
                    "Policy " + ref + " is " + previous + " and can no longer be paid. Please buy a new policy.");
        }
        policy.setPaymentMethod(paymentMethod);
        String receiptRef = receipt(policy, paymentMethod);
        // The receipt settles the period's instalment on the due schedule (NS-SH04-001/003).
        billing.collect(policy, period.start(), period.end(), period.purpose(), receiptRef, actor);
        em.persist(new PremiumPayment(policy, period.start(), period.end(), paymentMethod, receiptRef, transactionId,
                actor));
        boolean first = PENDING_PAYMENT.equals(previous);
        // Cover applies from its start date on a first premium, and from today when a premium resumes it.
        history.record(WorkflowHistory.POLICY, ref, policy.getCustomerName(),
                first ? "Payment Received" : "Premium Received",
                previous, policy.getStatus(), details, actor, first ? policy.getCoverStartDate() : today);
        // Downstream effects (subledger, commission, reinsurance, messages) follow from these events, after commit.
        if (first) {
            Map<String, Object> issued = new LinkedHashMap<>();
            issued.put("policyRef", ref);
            issued.put("customerName", policy.getCustomerName());
            issued.put("product", policy.getProduct());
            issued.put("sumAssured", policy.getAmount());
            issued.put("premium", policy.getPremium().toPlainString());
            // The versions the contract was issued under (NS-API-003): consumers never look them up later.
            issued.put("productVersion", terms(policy).getLabel());
            issued.put("productVersionId", policy.getProductVersionId());
            issued.put("rateTableVersion", policy.getRateTableVersion());
            issued.put("quoteRef", policy.getQuoteRef());
            issued.put("quoteVersion", policy.getQuoteVersion());
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
        if (ACTIVE_NO_COVER.equals(previous) && !reinstatementHeld) {
            publishReinstated(policy, "Premium received", actor);
        }
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
        policy.terminated("CANCELLATION", LocalDate.now().plusDays(1));
        policy.setPaymentDueBy(null);
        policy.review(actor, note);
        // Cover runs to the end of the day it is cancelled: the cancellation applies from the next day.
        history.record(WorkflowHistory.POLICY, ref, policy.getCustomerName(), "Cancelled", previous,
                CANCELLED, PENDING_PAYMENT.equals(previous) ? note
                        : (coolingOff ? "Cooling-off cancellation within " + COOLING_OFF_DAYS
                                + " days of the policy start: premiums refundable unless an insured event occurred · "
                                : "Cancelled on the policyholder's written request · ") + note, actor,
                LocalDate.now().plusDays(1));
        outbox.publish(Events.POLICY_CANCELLED, ref, Map.of("policyRef", ref, "customerName",
                policy.getCustomerName(), "product", policy.getProduct(), "reason", note), actor);
        publishTerminated(policy, "CANCELLATION", note, actor);
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
            // NS-SH04-004: money that may still arrive or be applied holds the cover decision.
            if (!billing.unresolvedPayments(policy.getRef()).isEmpty()) {
                continue;
            }
            policy.setStatus(ACTIVE_NO_COVER);
            // Cover ended the day after grace ran out, whenever the sweep notices it (NS-DAT-002).
            history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Cover Ended", ACTIVE,
                    ACTIVE_NO_COVER, "Grace period ended " + policy.getGracePeriodEnd() + " with the premium due "
                            + policy.getNextPremiumDue() + " unpaid. The policy stays in force without cover for "
                            + NO_COVER_MONTHS + " months; paying resumes cover with no new waiting period.",
                    WorkflowHistory.SYSTEM, policy.getGracePeriodEnd().plusDays(1));
            Map<String, Object> lapsed = new LinkedHashMap<>();
            lapsed.put("policyRef", policy.getRef());
            lapsed.put("customerName", policy.getCustomerName());
            lapsed.put("product", policy.getProduct());
            lapsed.put("coverEndedOn", policy.getGracePeriodEnd().plusDays(1).toString());
            outbox.publish(Events.COVER_LAPSED, policy.getRef(), lapsed, WorkflowHistory.SYSTEM);
            moved.add(policy.getRef());
        }
        List<InsurancePolicy> noCover = em.createQuery(
                        "select p from InsurancePolicy p where p.status = :status", InsurancePolicy.class)
                .setParameter("status", ACTIVE_NO_COVER)
                .getResultList();
        for (InsurancePolicy policy : noCover) {
            LocalDate coverEnd = policy.getNextPremiumDue() != null ? policy.getNextPremiumDue() : policy.getGracePeriodEnd();
            if (coverEnd == null || !coverEnd.plusMonths(NO_COVER_MONTHS).isBefore(today)
                    || !billing.unresolvedPayments(policy.getRef()).isEmpty()) {
                continue;
            }
            policy.setStatus(LAPSED);
            policy.setLapseDate(today);
            policy.terminated("LAPSE", today);
            publishTerminated(policy, "LAPSE", "No premium within " + NO_COVER_MONTHS + " months of cover ending",
                    WorkflowHistory.SYSTEM);
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
                || claim.getPayoutAmount() == null || claim.getPayoutAmount().compareTo(policy.getAmount()) < 0) {
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
        policy.terminated("DEATH", LocalDate.now());
        publishTerminated(policy, "DEATH", "Ended by claim " + claim.getRef(), actor);
        // The claim's event ended the cover: nothing after it is covered.
        LocalDate ended;
        try {
            ended = LocalDate.parse(claim.getEventDate().trim()).plusDays(1);
        } catch (RuntimeException unparseable) {
            ended = LocalDate.now().plusDays(1);
        }
        history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Claimed", previous, CLAIMED,
                "Ended by claim " + claim.getRef() + " (TC-24)", actor, ended);
    }

    boolean expireIfOverdue(InsurancePolicy policy) {
        if (!PENDING_PAYMENT.equals(policy.getStatus()) || policy.getPaymentDueBy() == null
                || !policy.getPaymentDueBy().isBefore(LocalDateTime.now())) {
            return false;
        }
        policy.setStatus(EXPIRED);
        policy.terminated("EXPIRY", LocalDate.now());
        history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Expired", PENDING_PAYMENT,
                EXPIRED, "Not paid by " + policy.getPaymentDueBy().withNano(0), WorkflowHistory.SYSTEM);
        return true;
    }

    // ── reinstatement and termination (NS-IL03-004, IL03 events) ──────────────────────────────────

    /** Whether the product's servicing rule holds a late premium's reinstatement for approval. */
    private boolean reinstatementNeedsApproval(String product) {
        return em.createQuery("select r.reinstatementNeedsApproval from ServicingRule r where r.product = :product",
                        Boolean.class)
                .setParameter("product", product).getResultStream().findFirst().orElse(false);
    }

    /** Approves a held reinstatement: cover resumes today with no new waiting period. */
    @Transactional
    public InsurancePolicy approveReinstatement(String ref, String note, String actor) {
        InsurancePolicy policy = policy(ref);
        if (!REINSTATEMENT_PENDING.equals(policy.getStatus())) {
            throw new IllegalArgumentException("Policy " + ref + " has no reinstatement awaiting approval.");
        }
        policy.setStatus(ACTIVE);
        history.record(WorkflowHistory.POLICY, ref, policy.getCustomerName(), "Reinstated", REINSTATEMENT_PENDING, ACTIVE,
                "Reinstatement approved: " + note, actor, LocalDate.now());
        publishReinstated(policy, note, actor);
        return policy;
    }

    /** Declines a held reinstatement: the policy stays in force without cover and the premium is refundable. */
    @Transactional
    public InsurancePolicy declineReinstatement(String ref, String note, String actor) {
        InsurancePolicy policy = policy(ref);
        if (!REINSTATEMENT_PENDING.equals(policy.getStatus())) {
            throw new IllegalArgumentException("Policy " + ref + " has no reinstatement awaiting approval.");
        }
        policy.setStatus(ACTIVE_NO_COVER);
        history.record(WorkflowHistory.POLICY, ref, policy.getCustomerName(), "Reinstatement Declined",
                REINSTATEMENT_PENDING, ACTIVE_NO_COVER, "Cover stays off; the premium received is refundable: " + note,
                actor);
        return policy;
    }

    /**
     * Ends an in-force contract for a stated cause (SURRENDER, MATURITY, DEATH or OTHER), from the given day. The
     * cause is kept on the policy and published, so no reader has to infer it from the status.
     */
    @Transactional
    public InsurancePolicy terminate(String ref, String cause, String note, LocalDate on, String actor) {
        InsurancePolicy policy = policy(ref);
        String previous = policy.getStatus();
        if (!Set.of(ACTIVE, ACTIVE_NO_COVER, REINSTATEMENT_PENDING).contains(previous)) {
            throw new IllegalArgumentException("Policy " + ref + " is " + previous + " and cannot be terminated.");
        }
        policy.setStatus(TERMINATED);
        policy.terminated(cause, on);
        history.record(WorkflowHistory.POLICY, ref, policy.getCustomerName(), "Terminated", previous, TERMINATED,
                cause.charAt(0) + cause.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ') + ": " + note, actor, on);
        publishTerminated(policy, cause, note, actor);
        return policy;
    }

    private void publishReinstated(InsurancePolicy policy, String note, String actor) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyRef", policy.getRef());
        data.put("customerName", policy.getCustomerName());
        data.put("product", policy.getProduct());
        data.put("reinstatedOn", LocalDate.now().toString());
        data.put("note", note == null ? "" : note);
        outbox.publish(Events.POLICY_REINSTATED, policy.getRef(), data, actor);
    }

    private void publishTerminated(InsurancePolicy policy, String cause, String note, String actor) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyRef", policy.getRef());
        data.put("customerName", policy.getCustomerName());
        data.put("product", policy.getProduct());
        data.put("reason", cause);
        data.put("terminatedOn", String.valueOf(policy.getTerminatedOn()));
        data.put("note", note == null ? "" : note);
        outbox.publish(Events.POLICY_TERMINATED, policy.getRef(), data, actor);
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
        int max = terms(policy).getMaxBeneficiaries();
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
        publishBeneficiaryChange(policy, "BENEFICIARY_ADDED", row, LocalDate.now(), actor);
        return row;
    }

    /** IL03-EVENTS: a beneficiary change is a policy change. References and the relationship only (NS-API-003). */
    private void publishBeneficiaryChange(InsurancePolicy policy, String change, PolicyBeneficiary row,
            LocalDate effective, String actor) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyRef", policy.getRef());
        data.put("customerName", policy.getCustomerName());
        data.put("product", policy.getProduct());
        data.put("change", change);
        data.put("beneficiaryId", row.getId());
        data.put("relationship", row.getRelationship());
        data.put("effectiveDate", effective.toString());
        outbox.publish(Events.POLICY_CHANGED, policy.getRef(), data, actor);
    }

    @Transactional
    public void removeBeneficiary(String ref, Long beneficiaryId, String actor) {
        InsurancePolicy policy = policy(ref);
        PolicyBeneficiary row = beneficiaries(policy).stream()
                .filter(item -> item.getId().equals(beneficiaryId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Beneficiary " + beneficiaryId + " is not on " + ref));
        boolean wasPrimary = row.isPrimary();
        // NS-DAT-002: end-dated, not deleted. They stop being a beneficiary now, so the roster drops them at once,
        // but they remain the beneficiary of record for anything that happened before today.
        LocalDate from = LocalDate.now();
        row.remove(from, actor);
        if (wasPrimary) {
            beneficiaries(policy).stream().filter(item -> !item.getId().equals(beneficiaryId)).findFirst()
                    .ifPresent(next -> next.setPrimary(true));
        }
        history.record(WorkflowHistory.POLICY, ref, policy.getCustomerName(), "Beneficiary Removed",
                policy.getStatus(), policy.getStatus(), row.getFullName() + " (" + row.getRelationship()
                        + "), no longer a beneficiary from " + from, actor, from);
        publishBeneficiaryChange(policy, "BENEFICIARY_REMOVED", row, from, actor);
    }

    /** The beneficiaries designated now. */
    List<PolicyBeneficiary> beneficiaries(InsurancePolicy policy) {
        return em.createQuery(
                        "select b from PolicyBeneficiary b where b.policy = :policy and b.removedFrom is null"
                                + " order by b.displayOrder, b.id", PolicyBeneficiary.class)
                .setParameter("policy", policy)
                .getResultList();
    }

    /** The beneficiaries designated on {@code date}, including any removed since (NS-SH05-002). */
    List<PolicyBeneficiary> beneficiariesOn(InsurancePolicy policy, LocalDate date) {
        return em.createQuery(
                        "select b from PolicyBeneficiary b where b.policy = :policy order by b.displayOrder, b.id",
                        PolicyBeneficiary.class)
                .setParameter("policy", policy)
                .getResultList()
                .stream()
                .filter(b -> b.inForceOn(date))
                .toList();
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

    /** The product terms version the policy was issued under: its waiting period, grace, beneficiary limit, wording. */
    public ProductVersion terms(InsurancePolicy policy) {
        return productTerms.of(policy);
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
        policy.setGracePeriodEnd(next.plusDays(terms(policy).getGracePeriodDays()));
        policy.setLapseDate(null);
    }

    /**
     * A receipt was returned or reversed (AT-SH04-02): the policy is paid only up to its first unsettled period, and
     * grace runs from there. When that grace is already over, cover ends now; otherwise the daily sweep decides.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void reviewCoverAfterReversal(InsurancePolicy policy, LocalDate unpaidFrom, String reason, String actor) {
        String previous = policy.getStatus();
        LocalDate graceEnd = unpaidFrom.plusDays(terms(policy).getGracePeriodDays());
        policy.setNextPremiumDue(unpaidFrom);
        policy.setGracePeriodEnd(graceEnd);
        String detail = reason + " · paid to " + unpaidFrom.minusDays(1) + "; grace ends " + graceEnd;
        LocalDate effective = LocalDate.now();
        boolean coverEnded = false;
        if (ACTIVE.equals(previous) && graceEnd.isBefore(LocalDate.now())) {
            policy.setStatus(ACTIVE_NO_COVER);
            // The cover ended when that grace ran out, in the past; recorded now, effective then (NS-DAT-002).
            effective = graceEnd.plusDays(1);
            detail += " · grace was already over, so cover ended on " + graceEnd;
            coverEnded = true;
        }
        history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Cover Review", previous,
                policy.getStatus(), detail, actor, effective);
        if (coverEnded) {
            // IL03-EVENTS: the same CoverLapsed the daily sweep publishes, when a reversal is what ends the cover.
            Map<String, Object> lapsed = new LinkedHashMap<>();
            lapsed.put("policyRef", policy.getRef());
            lapsed.put("customerName", policy.getCustomerName());
            lapsed.put("product", policy.getProduct());
            lapsed.put("coverEndedOn", effective.toString());
            lapsed.put("cause", "RECEIPT_REVERSED");
            outbox.publish(Events.COVER_LAPSED, policy.getRef(), lapsed, actor);
        }
    }

    /** Months one premium pays for, from the tier's premium frequency; monthly when the policy has no tier. */
    public int months(InsurancePolicy policy) {
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
        row.setAmount(policy.getPremium().setScale(2, RoundingMode.HALF_UP));
        row.setPremium(BigDecimal.ZERO);
        row.setStatus("Paid");
        row.setRecordDate(LocalDate.now().format(DISPLAY));
        row.setDisplayOrder(0);
        em.persist(row);
        return row.getRef();
    }

    /** The policy's customer by reference, never by display name (NS-SH01-001). */
    private CustomerAccount account(InsurancePolicy policy) {
        return em.createQuery("select c from CustomerAccount c where c.ref = :ref", CustomerAccount.class)
                .setParameter("ref", policy.getCustomerRef())
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Customer " + policy.getCustomerRef() + " not found"));
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
