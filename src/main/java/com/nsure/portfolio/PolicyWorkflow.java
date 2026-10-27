package com.nsure.portfolio;

import com.nsure.common.exception.NotFoundException;
import com.nsure.controls.ControlKey;
import com.nsure.controls.ControlService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Policy lifecycle, as on the demo server (policy_management/tasks.py, api.py create_pending_policy / pay_policy,
 * nsure_integrations/payments.activate_policy):
 *
 * <pre>
 *   buy ─▶ PENDING PAYMENT ──paid──▶ ACTIVE ──grace period ends unpaid (daily)──▶ LAPSED
 *               │      └──payment window passes (hourly, or on a payment attempt)──▶ EXPIRED
 *               └──cancel (staff, with a reason)──▶ CANCELLED
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

    /** Payment methods the demo server's checkout offers. */
    public static final List<String> PAYMENT_METHODS = List.of("Orange Money", "MyZaka", "Card", "Bank EFT");
    static final int MAX_BENEFICIARIES = 5;

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final EntityManager em;
    private final ControlService controls;
    private final WorkflowHistory history;

    public PolicyWorkflow(EntityManager em, ControlService controls, WorkflowHistory history) {
        this.em = em;
        this.controls = controls;
        this.history = history;
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
        InsurancePolicy policy = policy(ref);
        String paymentMethod = paymentMethod(method);
        if (expireIfOverdue(policy)) {
            throw new IllegalArgumentException("Policy " + ref + " is " + EXPIRED
                    + ": its payment window has passed and it can no longer be paid. Please buy a new policy.");
        }
        String previous = policy.getStatus();
        LocalDate today = LocalDate.now();
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
            default -> throw new IllegalArgumentException(
                    "Policy " + ref + " is " + previous + " and can no longer be paid. Please buy a new policy.");
        }
        policy.setPaymentMethod(paymentMethod);
        receipt(policy, paymentMethod);
        history.record(WorkflowHistory.POLICY, ref, policy.getCustomerName(),
                PENDING_PAYMENT.equals(previous) ? "Payment Received" : "Premium Received",
                previous, policy.getStatus(), details, actor);
        return policy;
    }

    /** Staff cancel a policy that was never paid (demo server: Pending Payment → Cancel Policy). */
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
        if (!PENDING_PAYMENT.equals(policy.getStatus())) {
            throw new IllegalArgumentException("Only a policy awaiting payment can be cancelled; " + ref + " is "
                    + policy.getStatus() + ".");
        }
        policy.setStatus(CANCELLED);
        policy.setPaymentDueBy(null);
        policy.review(actor, note);
        history.record(WorkflowHistory.POLICY, ref, policy.getCustomerName(), "Cancelled", PENDING_PAYMENT,
                CANCELLED, note, actor);
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

    /** Active with the grace period over and no premium → Lapsed (demo server auto_lapse_policies). Runs daily. */
    @Transactional
    public List<String> lapseOverdue() {
        LocalDate today = LocalDate.now();
        List<InsurancePolicy> overdue = em.createQuery(
                        "select p from InsurancePolicy p where p.status = :status and p.gracePeriodEnd is not null"
                                + " and p.gracePeriodEnd < :today", InsurancePolicy.class)
                .setParameter("status", ACTIVE)
                .setParameter("today", today)
                .getResultList();
        for (InsurancePolicy policy : overdue) {
            policy.setStatus(LAPSED);
            policy.setLapseDate(today);
            history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Lapsed", ACTIVE,
                    LAPSED, "Grace period ended " + policy.getGracePeriodEnd() + " with the premium due "
                            + policy.getNextPremiumDue() + " unpaid", WorkflowHistory.SYSTEM);
        }
        return overdue.stream().map(InsurancePolicy::getRef).toList();
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
        if (existing.size() >= MAX_BENEFICIARIES) {
            throw new IllegalArgumentException("A policy can have at most " + MAX_BENEFICIARIES + " beneficiaries.");
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

    InsurancePolicy policy(String ref) {
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
    private int months(InsurancePolicy policy) {
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

    private void receipt(InsurancePolicy policy, String method) {
        PremiumReceipt row = new PremiumReceipt();
        int max = 0;
        for (String ref : em.createQuery("select r.ref from PremiumReceipt r", String.class).getResultList()) {
            max = Math.max(max, Integer.parseInt(ref.replaceAll("[^0-9]", "")));
        }
        row.setRef(String.format("INV-%04d", max + 1));
        row.setCustomerName(policy.getCustomerName());
        row.setProduct(policy.getProduct());
        row.setAmount(policy.getPremium().setScale(0, RoundingMode.UP).intValue());
        row.setPremium(BigDecimal.ZERO);
        row.setStatus("Paid");
        row.setRecordDate(LocalDate.now().format(DISPLAY));
        row.setDisplayOrder(0);
        em.persist(row);
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
