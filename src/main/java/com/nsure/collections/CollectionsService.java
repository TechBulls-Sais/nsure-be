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

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final BillingService billing;
    private final PolicyWorkflow policies;
    private final Outbox outbox;
    private final ReferenceNumbers references;
    private final StaffAlertService alerts;

    private final com.nsure.integration.Operations operations;
    /** How far ahead of its period an instalment is issued (NS-SH04-001; demo default 14 days, D10). */
    private final int billingLeadDays;

    public CollectionsService(EntityManager em, PlatformSecurityContext security, BillingService billing,
            PolicyWorkflow policies, Outbox outbox, ReferenceNumbers references, StaffAlertService alerts,
            com.nsure.integration.Operations operations,
            @org.springframework.beans.factory.annotation.Value("${nsure.collections.billing-lead-days:14}")
            int billingLeadDays) {
        this.billingLeadDays = billingLeadDays;
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
        InsurancePolicy policy = policies.policy(policyRef);
        return billing.account(policy, policies.months(policy));
    }

    /**
     * Asks for a premium receipt to be reversed because the bank returned, reversed or charged it back (NS-SH04-005,
     * AT-SH04-02): nothing changes until a different person approves the request. The original receipt is named and
     * kept; a receipt can have one open request at a time.
     */
    @Transactional
    public CollectionsViews.ReversalRequestView reverse(String receiptRef, JsonNode body) {
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
        if (!em.createQuery("select r.id from ReversalRequest r where r.receiptRef = :ref and r.status = :pending",
                        Long.class)
                .setParameter("ref", receiptRef).setParameter("pending", ReversalRequest.PENDING)
                .setMaxResults(1).getResultList().isEmpty()) {
            throw new ConflictException("A reversal of receipt " + receiptRef + " is already waiting for approval.");
        }
        ReversalRequest request = new ReversalRequest(references.next("RVQ-", 6, () -> 0L), receiptRef,
                payment.getPolicyRef(), kind, payment.getAmount(), reason, user.getUsername());
        em.persist(request);
        return view(request, null);
    }

    @Transactional(readOnly = true)
    public List<CollectionsViews.ReversalRequestView> reversalRequests() {
        security.authenticatedUser().validateHasPermissionTo("READ_FINANCE");
        return em.createQuery("select r from ReversalRequest r order by r.id desc", ReversalRequest.class)
                .setMaxResults(200).getResultStream().map(r -> view(r, null)).toList();
    }

    /** The finance checker approves a reversal someone else asked for: it is carried out now. */
    @Transactional
    public CollectionsViews.ReversalRequestView approveReversal(String ref, JsonNode body) {
        AppUser checker = security.authenticatedUser();
        checker.validateHasPermissionTo("APPROVE_JOURNAL");
        ReversalRequest request = pendingReversal(ref);
        if (checker.getUsername().equalsIgnoreCase(request.getRequestedBy())) {
            throw new com.nsure.security.exception.NoAuthorizationException("You asked for reversal " + ref
                    + "; someone else must approve it.");
        }
        CollectionsViews.ReversalView done = reverseConfirmed(request.getReceiptRef(), request.getKind(),
                request.getReason() + " (requested by " + request.getRequestedBy() + ")", checker.getUsername());
        request.decide(ReversalRequest.APPROVED, checker.getUsername(), optional(body, "note"), done.ref());
        return view(request, done);
    }

    @Transactional
    public CollectionsViews.ReversalRequestView rejectReversal(String ref, JsonNode body) {
        AppUser checker = security.authenticatedUser();
        checker.validateHasPermissionTo("APPROVE_JOURNAL");
        ReversalRequest request = pendingReversal(ref);
        if (checker.getUsername().equalsIgnoreCase(request.getRequestedBy())) {
            throw new com.nsure.security.exception.NoAuthorizationException("You asked for reversal " + ref
                    + "; someone else must decide it.");
        }
        request.decide(ReversalRequest.REJECTED, checker.getUsername(), required(body, "note", 5, 500), null);
        return view(request, null);
    }

    private ReversalRequest pendingReversal(String ref) {
        ReversalRequest request = em.createQuery("select r from ReversalRequest r where r.ref = :ref",
                        ReversalRequest.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Reversal request " + ref + " not found"));
        if (!ReversalRequest.PENDING.equals(request.getStatus())) {
            throw new ConflictException("Reversal request " + ref + " is already "
                    + request.getStatus().toLowerCase(Locale.ROOT) + ".");
        }
        return request;
    }

    private static CollectionsViews.ReversalRequestView view(ReversalRequest r, CollectionsViews.ReversalView done) {
        return new CollectionsViews.ReversalRequestView(r.getRef(), r.getReceiptRef(), r.getPolicyRef(), r.getKind(),
                r.getAmount(), r.getReason(), r.getStatus(), r.getRequestedBy(), r.getRequestedAt(), r.getDecidedBy(),
                r.getDecidedAt(), r.getDecisionNote(), r.getReversalRef(),
                done == null ? null : done.paidTo(), done == null ? null : done.policyStatus());
    }

    // ── the instalment ledger and re-allocation ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CollectionsViews.LedgerRowView> ledger(String status, String policyRef, int limit) {
        security.authenticatedUser().validateHasPermissionTo("READ_FINANCE");
        return billing.ledger(status, policyRef, limit);
    }

    /**
     * Asks for money applied to one instalment to be moved to another instalment of the same policy: nothing changes
     * until a different person approves it. An allocation can have one open request at a time.
     */
    @Transactional
    public CollectionsViews.ReallocationRequestView requestReallocation(String allocationRef, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_FINANCE");
        Instalment to = billing.byRef(required(body, "toInstalment", 1, 20));
        String reason = required(body, "reason", 10, 500);
        Allocation allocation = billing.allocationByRef(allocationRef);
        String refusal = billing.reallocationRefusal(allocation, to);
        if (refusal != null) {
            throw new IllegalArgumentException(refusal);
        }
        if (!em.createQuery("select r.id from ReallocationRequest r where r.allocationRef = :ref"
                        + " and r.status = :pending", Long.class)
                .setParameter("ref", allocationRef).setParameter("pending", ReallocationRequest.PENDING)
                .setMaxResults(1).getResultList().isEmpty()) {
            throw new ConflictException("A re-allocation of " + allocationRef + " is already waiting for approval.");
        }
        ReallocationRequest request = new ReallocationRequest(references.next("RAQ-", 6, () -> 0L), allocation, to,
                reason, user.getUsername());
        em.persist(request);
        return view(request, null, null);
    }

    @Transactional(readOnly = true)
    public List<CollectionsViews.ReallocationRequestView> reallocationRequests() {
        security.authenticatedUser().validateHasPermissionTo("READ_FINANCE");
        return em.createQuery("select r from ReallocationRequest r order by r.id desc", ReallocationRequest.class)
                .setMaxResults(200).getResultStream().map(r -> view(r, null, null)).toList();
    }

    /**
     * The finance checker approves a move someone else asked for. When it leaves an earlier period unpaid, the
     * policy's paid-to and cover are reviewed the same way as after a reversal.
     */
    @Transactional
    public CollectionsViews.ReallocationRequestView approveReallocation(String ref, JsonNode body) {
        AppUser checker = security.authenticatedUser();
        checker.validateHasPermissionTo("APPROVE_JOURNAL");
        ReallocationRequest request = pendingReallocation(ref);
        if (checker.getUsername().equalsIgnoreCase(request.getRequestedBy())) {
            throw new com.nsure.security.exception.NoAuthorizationException("You asked for re-allocation " + ref
                    + "; someone else must approve it.");
        }
        InsurancePolicy policy = policies.policy(request.getPolicyRef());
        em.lock(policy, LockModeType.PESSIMISTIC_WRITE);
        Allocation from = billing.allocationByRef(request.getAllocationRef());
        Instalment to = billing.byRef(request.getToInstalmentRef());
        Allocation moved = billing.reallocate(from, to, request.getRef(), checker.getUsername());
        request.decide(ReallocationRequest.APPROVED, checker.getUsername(), optional(body, "note"), moved.getRef());

        LocalDate unpaidFrom = billing.firstUnsettledStart(policy.getRef());
        if (unpaidFrom != null && policy.getNextPremiumDue() != null
                && unpaidFrom.isBefore(policy.getNextPremiumDue())) {
            policies.reviewCoverAfterReversal(policy, unpaidFrom, "Re-allocation " + ref + " moved BWP "
                    + request.getAmount().toPlainString() + " from " + request.getFromInstalmentRef() + " to "
                    + request.getToInstalmentRef() + ": " + request.getReason(), clip(checker.getUsername(), 100));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestRef", request.getRef());
        data.put("policyRef", policy.getRef());
        data.put("sourceRef", request.getSourceRef());
        data.put("fromAllocationRef", from.getRef());
        data.put("toAllocationRef", moved.getRef());
        data.put("fromInstalmentRef", request.getFromInstalmentRef());
        data.put("toInstalmentRef", request.getToInstalmentRef());
        data.put("amount", request.getAmount().toPlainString());
        data.put("requestedBy", request.getRequestedBy());
        outbox.publish(Events.RECEIPT_REALLOCATED, policy.getRef(), data, clip(checker.getUsername(), 100));
        return view(request, billing.account(policy).paidTo(), policy.getStatus());
    }

    @Transactional
    public CollectionsViews.ReallocationRequestView rejectReallocation(String ref, JsonNode body) {
        AppUser checker = security.authenticatedUser();
        checker.validateHasPermissionTo("APPROVE_JOURNAL");
        ReallocationRequest request = pendingReallocation(ref);
        if (checker.getUsername().equalsIgnoreCase(request.getRequestedBy())) {
            throw new com.nsure.security.exception.NoAuthorizationException("You asked for re-allocation " + ref
                    + "; someone else must decide it.");
        }
        request.decide(ReallocationRequest.REJECTED, checker.getUsername(), required(body, "note", 5, 500), null);
        return view(request, null, null);
    }

    private ReallocationRequest pendingReallocation(String ref) {
        ReallocationRequest request = em.createQuery("select r from ReallocationRequest r where r.ref = :ref",
                        ReallocationRequest.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Re-allocation request " + ref + " not found"));
        if (!ReallocationRequest.PENDING.equals(request.getStatus())) {
            throw new ConflictException("Re-allocation request " + ref + " is already "
                    + request.getStatus().toLowerCase(Locale.ROOT) + ".");
        }
        return request;
    }

    private static CollectionsViews.ReallocationRequestView view(ReallocationRequest r, LocalDate paidTo,
            String policyStatus) {
        return new CollectionsViews.ReallocationRequestView(r.getRef(), r.getAllocationRef(), r.getPolicyRef(),
                r.getSourceRef(), r.getFromInstalmentRef(), r.getToInstalmentRef(), r.getAmount(), r.getReason(),
                r.getStatus(), r.getRequestedBy(), r.getRequestedAt(), r.getDecidedBy(), r.getDecidedAt(),
                r.getDecisionNote(), r.getNewAllocationRef(), paidTo, policyStatus);
    }

    private static String optional(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field) || body.get(field).asText().isBlank()) {
            return null;
        }
        String value = body.get(field).asText().trim();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    /**
     * Carries out a reversal already authorised: a bank return read from the rail, or a staff reversal a second person
     * approved. The caller has checked who may do it.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public CollectionsViews.ReversalView reverseConfirmed(String receiptRef, String kind, String reason, String actor) {
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
        billing.reverseAllocations(receiptRef, reversalRef, actor);
        payment.reverse();
        em.createQuery("select r from PremiumReceipt r where r.ref = :ref", PremiumReceipt.class)
                .setParameter("ref", receiptRef).getResultStream().findFirst()
                .ifPresent(r -> r.setStatus("Reversed"));
        if (payment.getTransactionId() != null) {
            PaymentTransaction transaction = em.find(PaymentTransaction.class, payment.getTransactionId());
            if (transaction != null && PaymentTransaction.SETTLED.equals(transaction.getStatus())) {
                // NS-SH04-002: an unpaid return and a reversal or chargeback are distinct transaction states.
                if (ReceiptReversal.RETURNED.equals(kind)) {
                    transaction.returned(kind + ": " + reason);
                } else {
                    transaction.reversed(kind + ": " + reason);
                }
            }
        }
        ReceiptReversal reversal = new ReceiptReversal(reversalRef, receiptRef, policy.getRef(), kind,
                payment.getAmount(), reason, payment.getTransactionId(), clip(actor, 100));
        em.persist(reversal);

        LocalDate unpaidFrom = billing.firstUnsettledStart(policy.getRef());
        if (unpaidFrom != null) {
            policies.reviewCoverAfterReversal(policy, unpaidFrom, "Receipt " + receiptRef + " "
                    + kind.toLowerCase(Locale.ROOT) + ": " + reason, clip(actor, 100));
        }
        alerts.raise("COVER_REVIEW", "UPDATE_POLICY", "Cover review: premium reversed on " + policy.getRef(),
                "Receipt " + receiptRef + " (BWP " + payment.getAmount().toPlainString() + ") was "
                        + kind.toLowerCase(Locale.ROOT) + ". " + reason + " The policy is now " + policy.getStatus()
                        + (unpaidFrom == null ? "." : ", paid to " + unpaidFrom.minusDays(1) + "."),
                "COVER_REVIEW:" + reversalRef, clip(actor, 100));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyRef", policy.getRef());
        data.put("receiptRef", receiptRef);
        data.put("reversalRef", reversalRef);
        data.put("kind", kind);
        data.put("amount", payment.getAmount().toPlainString());
        outbox.publish(Events.PAYMENT_RETURNED, policy.getRef(), data, clip(actor, 100));
        return new CollectionsViews.ReversalView(reversalRef, receiptRef, policy.getRef(), kind, payment.getAmount(),
                reason, reversal.getReversedBy(), reversal.getReversedAt(),
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
        LocalDate horizon = asOf.plusDays(billingLeadDays);
        List<String> issued = new ArrayList<>();
        for (InsurancePolicy policy : em.createQuery("select p from InsurancePolicy p where p.status in :statuses"
                        + " and p.nextPremiumDue is not null and p.nextPremiumDue <= :horizon", InsurancePolicy.class)
                .setParameter("statuses", Set.of(PolicyWorkflow.ACTIVE, PolicyWorkflow.GRACE,
                        PolicyWorkflow.ACTIVE_NO_COVER))
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

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
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
