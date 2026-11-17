package com.nsure.collections;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.ExpectedVersion;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.controls.StaffAlertService;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.payment.PaymentTransaction;
import com.nsure.portfolio.InsurancePolicy;
import com.nsure.portfolio.PolicyWorkflow;
import com.nsure.portfolio.PremiumPayment;
import com.nsure.portfolio.PremiumReceipt;
import com.nsure.portfolio.ReferenceNumbers;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Collections operations on individual policies (SH04): the billing account, receipt reversals with a controlled
 * cover review, schedule adjustments and the billing run that issues upcoming instalments.
 */
@Service
public class CollectionsService {

    private static final Set<String> KINDS = Set.of(ReceiptReversal.RETURNED, ReceiptReversal.REVERSED,
            ReceiptReversal.CHARGEBACK);
    /** How far ahead of its due date an instalment is issued. */
    static final int BILLING_LEAD_DAYS = 14;

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final BillingService billing;
    private final PolicyWorkflow policies;
    private final Outbox outbox;
    private final ReferenceNumbers references;
    private final StaffAlertService alerts;

    private final com.nsure.integration.Operations operations;

    public CollectionsService(EntityManager em, PlatformSecurityContext security, BillingService billing,
            PolicyWorkflow policies, Outbox outbox, ReferenceNumbers references, StaffAlertService alerts,
            com.nsure.integration.Operations operations) {
        this.operations = operations;
        this.em = em;
        this.security = security;
        this.billing = billing;
        this.policies = policies;
        this.outbox = outbox;
        this.references = references;
        this.alerts = alerts;
    }

    @Transactional(readOnly = true)
    public CollectionsViews.AccountView account(String policyRef) {
        AppUser user = security.authenticatedUser();
        if (!user.hasPermissionTo("READ_POLICY") && !user.hasPermissionTo("READ_FINANCE")) {
            user.validateHasPermissionTo("READ_POLICY");
        }
        return billing.account(policies.policy(policyRef));
    }

    /**
     * The bank returned, reversed or charged back a premium receipt (NS-SH04-005, AT-SH04-02). The receipt and its
     * allocations are kept and marked reversed; the period reopens so it can be paid again; the policy is paid only
     * up to its first unsettled period, with a cover review; PaymentReturned reverses the premium in the subledger.
     */
    @Transactional
    public CollectionsViews.ReversalView reverse(String receiptRef, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_FINANCE");
        String kind = required(body, "kind", 6, 20).toUpperCase(Locale.ROOT);
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("Kind must be RETURNED, REVERSED or CHARGEBACK.");
        }
        String reason = required(body, "reason", 10, 500);
        PremiumPayment payment = em.createQuery("select p from PremiumPayment p where p.receiptRef = :ref",
                        PremiumPayment.class)
                .setParameter("ref", receiptRef).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("No premium receipt " + receiptRef + "."));
        if (!"SUCCESSFUL".equals(payment.getStatus())) {
            throw new ConflictException("Receipt " + receiptRef + " is already " + payment.getStatus().toLowerCase(Locale.ROOT) + ".");
        }
        InsurancePolicy policy = policies.policy(payment.getPolicyRef());
        em.lock(policy, LockModeType.PESSIMISTIC_WRITE);
        String reversalRef = references.next("REV-", 6, () -> 0L);
        billing.reverseAllocations(receiptRef, reversalRef, user.getUsername());
        payment.reverse();
        em.createQuery("select r from PremiumReceipt r where r.ref = :ref", PremiumReceipt.class)
                .setParameter("ref", receiptRef).getResultStream().findFirst()
                .ifPresent(r -> r.setStatus("Reversed"));
        if (payment.getTransactionId() != null) {
            PaymentTransaction transaction = em.find(PaymentTransaction.class, payment.getTransactionId());
            if (transaction != null && PaymentTransaction.SETTLED.equals(transaction.getStatus())) {
                transaction.returned(kind + ": " + reason);
            }
        }
        ReceiptReversal reversal = new ReceiptReversal(reversalRef, receiptRef, policy.getRef(), kind,
                payment.getAmount(), reason, payment.getTransactionId(), user.getUsername());
        em.persist(reversal);

        LocalDate unpaidFrom = billing.firstUnsettledStart(policy.getRef());
        if (unpaidFrom != null) {
            policies.reviewCoverAfterReversal(policy, unpaidFrom, "Receipt " + receiptRef + " "
                    + kind.toLowerCase(Locale.ROOT) + ": " + reason, user.getUsername());
        }
        alerts.raise("COVER_REVIEW", "UPDATE_POLICY", "Cover review: premium reversed on " + policy.getRef(),
                "Receipt " + receiptRef + " (BWP " + payment.getAmount().toPlainString() + ") was "
                        + kind.toLowerCase(Locale.ROOT) + ". " + reason + " The policy is now " + policy.getStatus()
                        + (unpaidFrom == null ? "." : ", paid to " + unpaidFrom.minusDays(1) + "."),
                "COVER_REVIEW:" + reversalRef, user.getUsername());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyRef", policy.getRef());
        data.put("receiptRef", receiptRef);
        data.put("reversalRef", reversalRef);
        data.put("kind", kind);
        data.put("amount", payment.getAmount().toPlainString());
        outbox.publish(Events.PAYMENT_RETURNED, policy.getRef(), data, user.getUsername());
        return new CollectionsViews.ReversalView(reversalRef, receiptRef, policy.getRef(), kind, payment.getAmount(),
                reason, user.getUsername(), reversal.getReversedAt(),
                unpaidFrom == null ? null : unpaidFrom.minusDays(1), policy.getStatus());
    }

    /** A signed correction to an instalment, with a reason; the billed amount itself never changes. */
    @Transactional
    public CollectionsViews.AccountView adjust(String instalmentRef, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_FINANCE");
        Instalment instalment = billing.byRef(instalmentRef);
        ExpectedVersion.check("Instalment " + instalmentRef, instalment.getRowVersion());
        BigDecimal amount;
        try {
            amount = new BigDecimal(required(body, "amount", 1, 30)).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("amount must be an amount like -25.00 or 10.50.");
        }
        if (amount.signum() == 0) {
            throw new IllegalArgumentException("An adjustment of zero changes nothing.");
        }
        billing.adjust(instalmentRef, amount, required(body, "reason", 10, 500), user.getUsername());
        return billing.account(policies.policy(instalment.getPolicyRef()));
    }

    /**
     * Issues the instalment of every in-force policy whose next premium falls due within the lead time
     * (NS-SH04-001). Running it again issues nothing new.
     */
    @Transactional
    public CollectionsViews.BillingRunView billingRun(LocalDate asOf, String actor) {
        LocalDate horizon = asOf.plusDays(BILLING_LEAD_DAYS);
        List<String> issued = new ArrayList<>();
        for (InsurancePolicy policy : em.createQuery("select p from InsurancePolicy p where p.status in :statuses"
                        + " and p.nextPremiumDue is not null and p.nextPremiumDue <= :horizon", InsurancePolicy.class)
                .setParameter("statuses", Set.of(PolicyWorkflow.ACTIVE, PolicyWorkflow.ACTIVE_NO_COVER))
                .setParameter("horizon", horizon)
                .getResultList()) {
            LocalDate start = policy.getNextPremiumDue();
            Long existing = em.createQuery("select count(i) from Instalment i where i.policyRef = :ref"
                    + " and i.periodStart = :start", Long.class)
                    .setParameter("ref", policy.getRef()).setParameter("start", start).getSingleResult();
            if (existing > 0) {
                continue;
            }
            int months = Math.max(1, policies.months(policy));
            Instalment instalment = billing.instalmentFor(policy, start, start.plusMonths(months).minusDays(1),
                    "RENEWAL", actor);
            issued.add(instalment.getRef());
        }
        return new CollectionsViews.BillingRunView(issued.size(), issued);
    }

    @Transactional
    public CollectionsViews.BillingRunView billingRunNow() {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_FINANCE");
        return billingRun(LocalDate.now(), user.getUsername());
    }

    /**
     * The billing run in the background (spec §10): answers at once with an operation to follow at
     * /operations/{id}. The run is one transaction, so a crash part-way issues nothing and running it again issues
     * each instalment once.
     */
    public com.nsure.integration.Operations.OperationView billingRunInBackground() {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_FINANCE");
        String actor = user.getUsername();
        LocalDate asOf = LocalDate.now();
        return operations.submit("BILLING_RUN", "MANAGE_FINANCE", user, () -> {
            CollectionsViews.BillingRunView run = billingRun(asOf, actor);
            return new com.nsure.integration.Operations.Outcome("BILLING-RUN-" + asOf, run);
        });
    }

    private static String required(JsonNode body, String field, int min, int max) {
        String value = body == null || !body.hasNonNull(field) ? "" : body.get(field).asText().trim();
        if (value.length() < min) {
            throw new IllegalArgumentException(field + " is required" + (min > 1 ? " (at least " + min + " characters)." : "."));
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(field + " is too long (at most " + max + " characters).");
        }
        return value;
    }
}
