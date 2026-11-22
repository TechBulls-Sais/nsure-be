package com.nsure.finance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.integration.InboundMessage;
import com.nsure.integration.Outbox;
import com.nsure.portfolio.InsurancePolicy;
import com.nsure.portfolio.PolicyWorkflow;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finance controls from the Solution Blueprint §7 that need a named owner and a second person:
 *
 * <ul>
 *   <li><b>Manual journals</b>: proposed with a reason (MANAGE_FINANCE), balanced, in an open period; posted only when
 *       someone else approves it (APPROVE_JOURNAL). Until then no balance moves.</li>
 *   <li><b>Suspense clearance</b>: cash in suspense is allocated to a policy (when it pays that policy's premium) or
 *       refunded, on request with a reason, applied only when someone else approves. Clearing posts a
 *       {@code SuspenseCleared} journal (Dr suspense, Cr bank) so the receipt is never counted twice; an allocation
 *       also collects the premium through the policy workflow.</li>
 * </ul>
 */
@Service
public class FinanceControls {

    static final String APPROVE = "APPROVE_JOURNAL";
    static final String ALLOCATE = "ALLOCATE";
    static final String REFUND = "REFUND";

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final Subledger subledger;
    private final PolicyWorkflow policies;
    private final Outbox outbox;
    private final ObjectMapper mapper;
    private final com.nsure.collections.BillingService billing;
    private final RefundControls refunds;

    public FinanceControls(EntityManager em, PlatformSecurityContext security, Subledger subledger,
            PolicyWorkflow policies, Outbox outbox, ObjectMapper mapper, com.nsure.collections.BillingService billing,
            RefundControls refunds) {
        this.refunds = refunds;
        this.billing = billing;
        this.em = em;
        this.security = security;
        this.subledger = subledger;
        this.policies = policies;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    // ── manual journals ──────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<FinanceViews.ManualJournalView> manualJournals() {
        security.authenticatedUser().validateHasPermissionTo("READ_FINANCE");
        return em.createQuery("select m from ManualJournal m order by m.id desc", ManualJournal.class)
                .setMaxResults(200).getResultStream().map(this::view).toList();
    }

    @Transactional
    public FinanceViews.ManualJournalView proposeManualJournal(JsonNode body) {
        AppUser maker = security.authenticatedUser();
        maker.validateHasPermissionTo("MANAGE_FINANCE");
        String description = required(body, "description", 3, 300);
        String reason = required(body, "reason", 10, 1000);
        LocalDate date;
        try {
            date = body.hasNonNull("accountingDate") ? LocalDate.parse(body.get("accountingDate").asText())
                    : LocalDate.now();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("accountingDate must be yyyy-MM-dd.");
        }
        List<FinanceViews.ManualLineView> lines = new ArrayList<>();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (JsonNode node : body.path("lines")) {
            String account = node.path("account").asText("").trim();
            if (!subledger.accountExists(account)) {
                throw new IllegalArgumentException("Unknown account " + account + ".");
            }
            BigDecimal dr = money(node.path("debit"));
            BigDecimal cr = money(node.path("credit"));
            if ((dr.signum() > 0) == (cr.signum() > 0) || dr.signum() < 0 || cr.signum() < 0) {
                throw new IllegalArgumentException("Each line is either a debit or a credit, greater than zero.");
            }
            lines.add(new FinanceViews.ManualLineView(account, dr, cr));
            debit = debit.add(dr);
            credit = credit.add(cr);
        }
        if (lines.size() < 2) {
            throw new IllegalArgumentException("A journal needs at least two lines.");
        }
        if (debit.compareTo(credit) != 0) {
            throw new IllegalArgumentException("The journal does not balance: debits " + debit.toPlainString()
                    + ", credits " + credit.toPlainString() + ".");
        }
        String ref = subledger.nextRef("MJ-", ManualJournal.class);
        ManualJournal journal = new ManualJournal(ref, description, reason, date, write(lines), debit,
                maker.getUsername());
        em.persist(journal);
        return view(journal);
    }

    @Transactional
    public FinanceViews.ManualJournalView approveManualJournal(String ref) {
        AppUser checker = checker();
        ManualJournal manual = pendingJournal(ref);
        if (checker.getUsername().equalsIgnoreCase(manual.getMadeBy())) {
            throw new NoAuthorizationException("You proposed manual journal " + ref + "; someone else must approve it.");
        }
        List<FinanceViews.ManualLineView> lines = read(manual.getLinesJson());
        List<String> accounts = lines.stream().map(FinanceViews.ManualLineView::account).toList();
        List<BigDecimal[]> amounts = lines.stream().map(l -> new BigDecimal[] {l.debit(), l.credit()}).toList();
        Journal journal = subledger.postManual(ref, manual.getDescription(), manual.getAccountingDate(), amounts,
                accounts, manual.getAmount(), checker.getUsername());
        manual.posted(journal.getRef(), checker.getUsername());
        return view(manual);
    }

    @Transactional
    public FinanceViews.ManualJournalView rejectManualJournal(String ref, JsonNode body) {
        AppUser checker = checker();
        ManualJournal manual = pendingJournal(ref);
        manual.rejected(required(body, "reason", 5, 1000), checker.getUsername());
        return view(manual);
    }

    // ── suspense clearance ───────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<FinanceViews.SuspenseView> suspense() {
        security.authenticatedUser().validateHasPermissionTo("READ_FINANCE");
        return em.createQuery("select m from InboundMessage m where m.status = :suspense or m.clearedBy is not null "
                        + "order by m.id desc", InboundMessage.class)
                .setParameter("suspense", InboundMessage.SUSPENSE)
                .setMaxResults(200).getResultStream().map(FinanceControls::suspenseView).toList();
    }

    @Transactional
    public FinanceViews.SuspenseView requestClearance(Long id, JsonNode body) {
        AppUser maker = security.authenticatedUser();
        maker.validateHasPermissionTo("MANAGE_FINANCE");
        InboundMessage receipt = inSuspense(id);
        if (receipt.getClearanceAction() != null) {
            throw new ConflictException("A clearance of this receipt is already waiting for approval.");
        }
        String action = required(body, "action", 3, 20).toUpperCase(Locale.ROOT);
        String reason = required(body, "reason", 10, 1000);
        String policyRef = null;
        String plan = null;
        if (ALLOCATE.equals(action)) {
            JsonNode lines = body.get("allocations");
            if (lines != null && lines.isArray() && !lines.isEmpty()) {
                // NS-SH04-003: split across policies, or part-pay an instalment; the rest stays unapplied.
                List<FinanceViews.SuspenseLine> parsed = lines(lines);
                checkLines(parsed, receipt.getUnapplied());
                plan = writeLines(parsed);
                policyRef = parsed.get(0).policyRef();
            } else {
                policyRef = required(body, "policyRef", 3, 40);
                allocationProblem(policyRef, receipt.getUnapplied());
            }
        } else if (REFUND.equals(action)) {
            // NS-SH04-005: a refund names where the money goes; the approver verifies it and a payout instruction pays it.
            String payee = required(body, "payeeName", 2, 150);
            String method = required(body, "payoutMethod", 2, 40);
            String account = required(body, "accountRef", 4, 60);
            refunds.raise("SUSPENSE", "SUS-" + receipt.getId(), receipt.getBusinessRef(), payee, method, account,
                    receipt.getUnapplied(), reason.length() <= 500 ? reason : reason.substring(0, 500),
                    maker.getUsername());
        } else {
            throw new IllegalArgumentException("Action must be ALLOCATE or REFUND.");
        }
        receipt.requestClearance(action, policyRef, plan, reason, maker.getUsername());
        return suspenseView(receipt);
    }

    @Transactional
    public FinanceViews.SuspenseView approveClearance(Long id) {
        return approveClearance(id, null);
    }

    /** {verificationNote?}: for a refund, how the approver verified the destination when it is no mandate on file. */
    @Transactional
    public FinanceViews.SuspenseView approveClearance(Long id, JsonNode body) {
        AppUser checker = checker();
        InboundMessage receipt = inSuspense(id);
        if (receipt.getClearanceAction() == null) {
            throw new ConflictException("Nothing is waiting for approval on this receipt.");
        }
        if (checker.getUsername().equalsIgnoreCase(receipt.getClearanceRequestedBy())) {
            throw new NoAuthorizationException("You requested this clearance; someone else must approve it.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inboundId", receipt.getId());
        data.put("providerRef", receipt.getProviderRef());
        data.put("action", receipt.getClearanceAction());
        if (ALLOCATE.equals(receipt.getClearanceAction())) {
            List<FinanceViews.SuspenseLine> lines;
            if (receipt.getClearanceAllocations() == null) {
                allocationProblem(receipt.getClearancePolicyRef(), receipt.getUnapplied());
                lines = List.of(new FinanceViews.SuspenseLine(receipt.getClearancePolicyRef(), receipt.getUnapplied()));
            } else {
                lines = readLines(receipt.getClearanceAllocations());
                checkLines(lines, receipt.getUnapplied());
            }
            BigDecimal applied = BigDecimal.ZERO;
            List<String> done = new ArrayList<>();
            for (FinanceViews.SuspenseLine line : lines) {
                InsurancePolicy policy = policies.policy(line.policyRef());
                PolicyWorkflow.Period period = policies.nextPeriod(policy);
                BigDecimal outstanding = billing.outstandingFor(policy, period.start());
                if (line.amount().compareTo(outstanding) == 0) {
                    // Settles the instalment: the premium is collected and cover follows, as for any payment.
                    policies.pay(line.policyRef(), "Bank EFT", "suspense:" + checker.getUsername());
                    done.add(line.policyRef() + " BWP " + line.amount().toPlainString() + " (instalment settled)");
                } else {
                    billing.applyPart(policy, period.start(), period.end(), period.purpose(), "SUS-" + receipt.getId(),
                            line.amount(), checker.getUsername());
                    done.add(line.policyRef() + " BWP " + line.amount().toPlainString() + " (part payment)");
                }
                applied = applied.add(line.amount());
            }
            data.put("policyRef", lines.get(0).policyRef());
            BigDecimal left = receipt.getUnapplied().subtract(applied);
            if (left.signum() == 0) {
                receipt.applied(applied);
                receipt.cleared(InboundMessage.APPLIED, lines.get(0).policyRef(), "Allocated from suspense: "
                        + String.join("; ", done) + " · " + receipt.getClearanceReason(), checker.getUsername());
            } else {
                receipt.partlyApplied(applied, "Applied " + String.join("; ", done) + " · BWP " + left.toPlainString()
                        + " still unapplied");
            }
            data.put("amount", applied.toPlainString());
        } else {
            data.put("amount", receipt.getUnapplied().toPlainString());
            RefundRequest refund = refunds.pendingFor("SUSPENSE", "SUS-" + receipt.getId());
            if (refund == null) {
                throw new ConflictException("This refund was asked for without a destination; reject it and ask again"
                        + " with the payee's account or wallet.");
            }
            // Verifies the destination, sends the payout instruction and raises RefundSettled once it is confirmed.
            refunds.pay(refund, checker, body);
            receipt.cleared(InboundMessage.REFUNDED, refund.getRef(), "Refunded from suspense by " + refund.getRef()
                    + " (payout " + refund.getPayoutRef() + "): " + receipt.getClearanceReason(), checker.getUsername());
            data.put("refundRef", refund.getRef());
        }
        outbox.publish(Events.SUSPENSE_CLEARED, receipt.getProviderRef(), data, checker.getUsername());
        return suspenseView(receipt);
    }

    @Transactional
    public FinanceViews.SuspenseView rejectClearance(Long id) {
        AppUser checker = checker();
        InboundMessage receipt = inSuspense(id);
        if (receipt.getClearanceAction() == null) {
            throw new ConflictException("Nothing is waiting for approval on this receipt.");
        }
        RefundRequest refund = refunds.pendingFor("SUSPENSE", "SUS-" + receipt.getId());
        if (refund != null) {
            refund.decide(RefundRequest.REJECTED, checker.getUsername(), "Suspense refund request rejected");
        }
        receipt.clearanceRejected();
        return suspenseView(receipt);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private AppUser checker() {
        AppUser checker = security.authenticatedUser();
        checker.validateHasPermissionTo(APPROVE);
        return checker;
    }

    private ManualJournal pendingJournal(String ref) {
        ManualJournal manual = em.createQuery("select m from ManualJournal m where m.ref = :ref", ManualJournal.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Manual journal " + ref + " not found"));
        if (!ManualJournal.PENDING.equals(manual.getStatus())) {
            throw new ConflictException("Manual journal " + ref + " is already " + manual.getStatus() + ".");
        }
        return manual;
    }

    private InboundMessage inSuspense(Long id) {
        InboundMessage receipt = em.find(InboundMessage.class, id);
        if (receipt == null) {
            throw new NotFoundException("Receipt " + id + " not found");
        }
        if (!InboundMessage.SUSPENSE.equals(receipt.getStatus())) {
            throw new ConflictException("Receipt " + id + " is " + receipt.getStatus() + ", not in suspense.");
        }
        return receipt;
    }

    /**
     * A whole-receipt allocation must pay exactly what the policy's next instalment still needs; anything else is
     * split (allocation lines) or refunded instead.
     */
    private void allocationProblem(String policyRef, BigDecimal amount) {
        InsurancePolicy policy = payablePolicy(policyRef);
        BigDecimal due = billing.outstandingFor(policy, policies.nextPeriod(policy).start());
        if (due.compareTo(amount) != 0) {
            throw new IllegalArgumentException("BWP " + amount.toPlainString() + " does not pay the premium of "
                    + policyRef + " (BWP " + due.toPlainString() + "). Split it or refund it instead.");
        }
    }

    /** Each line pays part or all of one policy's next instalment; together they fit in what is unapplied. */
    private void checkLines(List<FinanceViews.SuspenseLine> lines, BigDecimal unapplied) {
        BigDecimal total = BigDecimal.ZERO;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (FinanceViews.SuspenseLine line : lines) {
            if (!seen.add(line.policyRef())) {
                throw new IllegalArgumentException(line.policyRef() + " appears twice; put its amount on one line.");
            }
            if (line.amount() == null || line.amount().signum() <= 0) {
                throw new IllegalArgumentException("Each allocation needs an amount above zero.");
            }
            InsurancePolicy policy = payablePolicy(line.policyRef());
            BigDecimal due = billing.outstandingFor(policy, policies.nextPeriod(policy).start());
            if (line.amount().compareTo(due) > 0) {
                throw new IllegalArgumentException("BWP " + line.amount().toPlainString() + " is more than the BWP "
                        + due.toPlainString() + " due on " + line.policyRef() + ".");
            }
            total = total.add(line.amount());
        }
        if (total.compareTo(unapplied) > 0) {
            throw new IllegalArgumentException("The allocations total BWP " + total.toPlainString()
                    + " but only BWP " + unapplied.toPlainString() + " of this receipt is unapplied.");
        }
    }

    private InsurancePolicy payablePolicy(String policyRef) {
        InsurancePolicy policy = em.createQuery("select p from InsurancePolicy p where p.ref = :ref",
                        InsurancePolicy.class)
                .setParameter("ref", policyRef).getResultStream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No policy " + policyRef + "."));
        policies.nextPeriod(policy);
        return policy;
    }

    private List<FinanceViews.SuspenseLine> lines(JsonNode lines) {
        List<FinanceViews.SuspenseLine> parsed = new ArrayList<>();
        for (JsonNode line : lines) {
            String ref = line.hasNonNull("policyRef") ? line.get("policyRef").asText().trim() : "";
            if (ref.isEmpty()) {
                throw new IllegalArgumentException("Each allocation needs a policyRef.");
            }
            try {
                parsed.add(new FinanceViews.SuspenseLine(ref,
                        new BigDecimal(line.path("amount").asText()).setScale(2, RoundingMode.HALF_UP)));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Each allocation needs an amount like 120.50.");
            }
        }
        if (parsed.size() > 20) {
            throw new IllegalArgumentException("Split a receipt over at most 20 policies.");
        }
        return parsed;
    }

    private String writeLines(List<FinanceViews.SuspenseLine> lines) {
        try {
            return mapper.writeValueAsString(lines);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private List<FinanceViews.SuspenseLine> readLines(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<FinanceViews.SuspenseLine>>() { });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("The saved allocation lines cannot be read", ex);
        }
    }

    private FinanceViews.ManualJournalView view(ManualJournal m) {
        return new FinanceViews.ManualJournalView(m.getRef(), m.getDescription(), m.getReason(), m.getAccountingDate(),
                read(m.getLinesJson()), m.getAmount(), m.getStatus(), m.getMadeBy(), m.getMadeAt(), m.getCheckedBy(),
                m.getCheckedAt(), m.getDecisionNote(), m.getJournalRef());
    }

    static FinanceViews.SuspenseView suspenseView(InboundMessage m) {
        return new FinanceViews.SuspenseView(m.getId(), m.getProvider(), m.getProviderRef(), m.getBusinessRef(),
                m.getAmount(), m.getStatus(), m.getDetail(), m.getReceivedAt(), m.getClearanceAction(),
                m.getClearancePolicyRef(), m.getClearanceReason(), m.getClearanceRequestedBy(),
                m.getClearanceRequestedAt(), m.getClearedBy(), m.getClearedAt(), m.getUnapplied(),
                m.getClearanceAllocations());
    }

    private String write(List<FinanceViews.ManualLineView> lines) {
        try {
            return mapper.writeValueAsString(lines);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private List<FinanceViews.ManualLineView> read(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<FinanceViews.ManualLineView>>() { });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static BigDecimal money(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(node.asText()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Amounts must be numbers.");
        }
    }

    private static String required(JsonNode body, String field, int min, int max) {
        String value = body == null || !body.hasNonNull(field) ? "" : body.get(field).asText().trim();
        if (value.length() < min || value.length() > max) {
            throw new IllegalArgumentException(field + " is required (" + min + " to " + max + " characters).");
        }
        return value;
    }
}
