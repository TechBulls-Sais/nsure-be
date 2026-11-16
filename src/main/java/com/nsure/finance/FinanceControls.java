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

    public FinanceControls(EntityManager em, PlatformSecurityContext security, Subledger subledger,
            PolicyWorkflow policies, Outbox outbox, ObjectMapper mapper) {
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
        if (ALLOCATE.equals(action)) {
            policyRef = required(body, "policyRef", 3, 40);
            allocationProblem(policyRef, receipt.getAmount());
        } else if (!REFUND.equals(action)) {
            throw new IllegalArgumentException("Action must be ALLOCATE or REFUND.");
        }
        receipt.requestClearance(action, policyRef, reason, maker.getUsername());
        return suspenseView(receipt);
    }

    @Transactional
    public FinanceViews.SuspenseView approveClearance(Long id) {
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
        data.put("amount", receipt.getAmount().toPlainString());
        if (ALLOCATE.equals(receipt.getClearanceAction())) {
            String policyRef = receipt.getClearancePolicyRef();
            allocationProblem(policyRef, receipt.getAmount());
            // Suspense receipts arrive through the bank feed; the history records who approved the allocation.
            policies.pay(policyRef, "Bank EFT", "suspense:" + checker.getUsername());
            data.put("policyRef", policyRef);
            receipt.cleared(InboundMessage.APPLIED, policyRef, "Allocated from suspense to " + policyRef + ": "
                    + receipt.getClearanceReason(), checker.getUsername());
        } else {
            receipt.cleared(InboundMessage.REFUNDED, null, "Refunded from suspense: " + receipt.getClearanceReason(),
                    checker.getUsername());
        }
        outbox.publish(Events.SUSPENSE_CLEARED, receipt.getProviderRef(), data, checker.getUsername());
        return suspenseView(receipt);
    }

    @Transactional
    public FinanceViews.SuspenseView rejectClearance(Long id) {
        checker();
        InboundMessage receipt = inSuspense(id);
        if (receipt.getClearanceAction() == null) {
            throw new ConflictException("Nothing is waiting for approval on this receipt.");
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

    /** An allocation must pay exactly the policy's premium; anything else is refunded instead. */
    private void allocationProblem(String policyRef, BigDecimal amount) {
        InsurancePolicy policy = em.createQuery("select p from InsurancePolicy p where p.ref = :ref",
                        InsurancePolicy.class)
                .setParameter("ref", policyRef).getResultStream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No policy " + policyRef + "."));
        if (policy.getPremium() == null || policy.getPremium().compareTo(amount) != 0) {
            throw new IllegalArgumentException("BWP " + amount.toPlainString() + " does not pay the premium of "
                    + policyRef + " (BWP " + (policy.getPremium() == null ? "?" : policy.getPremium().toPlainString())
                    + "). Refund it instead.");
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
                m.getClearanceRequestedAt(), m.getClearedBy(), m.getClearedAt());
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
