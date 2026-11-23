package com.nsure.collections;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.Money;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.integration.InboundMessage;
import com.nsure.integration.Outbox;
import com.nsure.payment.PaymentSettlementService;
import com.nsure.payment.PaymentTransaction;
import com.nsure.portfolio.InsuranceApplication;
import com.nsure.portfolio.InsurancePolicy;
import com.nsure.portfolio.PolicyWorkflow;
import com.nsure.portfolio.PremiumPayment;
import com.nsure.portfolio.ReferenceNumbers;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Debit-order and mobile-money collection (NS-SH04-002): mandates, then a collection run that a second person
 * approves before anything is sent. The rail's response, never the submission, settles a premium; a confirmed
 * collection is applied to its instalment through the normal payment path (receipt, allocation, PremiumReceived),
 * or held in suspense when the policy can no longer take it. A failure leaves the instalment owed; a later bank
 * return reverses the receipt.
 */
@Service
public class CollectionRuns {

    static final Set<String> CHANNELS = Set.of(CollectionMandate.DEBIT_ORDER, CollectionMandate.MOBILE_WALLET);
    static final List<String> WALLETS = List.of("Orange Money", "MyZaka");

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final BillingService billing;
    private final PolicyWorkflow policies;
    private final CollectionsService collections;
    private final PaymentSettlementService settlement;
    private final CollectionRail rail;
    private final ReferenceNumbers references;
    private final Outbox outbox;
    private final int windowDays;

    public CollectionRuns(EntityManager em, PlatformSecurityContext security, BillingService billing,
            PolicyWorkflow policies, CollectionsService collections, PaymentSettlementService settlement,
            CollectionRail rail, ReferenceNumbers references, Outbox outbox,
            @Value("${nsure.collections.collection-window-days:7}") int windowDays) {
        this.em = em;
        this.security = security;
        this.billing = billing;
        this.policies = policies;
        this.collections = collections;
        this.settlement = settlement;
        this.rail = rail;
        this.references = references;
        this.outbox = outbox;
        this.windowDays = windowDays;
    }

    // ── mandates ─────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CollectionsViews.MandateView> mandates(String policyRef) {
        reader();
        return em.createQuery("select m from CollectionMandate m where m.policyRef = :ref order by m.id desc",
                        CollectionMandate.class)
                .setParameter("ref", policyRef).getResultStream().map(CollectionRuns::view).toList();
    }

    /**
     * {policyRef, channel DEBIT_ORDER|MOBILE_WALLET, institution, accountNumber, accountHolder?, debitDay (1–28, debit
     * orders)}. Replaces the policy's active mandate, which is kept as cancelled.
     */
    @Transactional
    public CollectionsViews.MandateView addMandate(JsonNode body) {
        AppUser user = user("MANAGE_FINANCE");
        InsurancePolicy policy = policies.policy(required(body, "policyRef", 3, 20));
        if (Set.of(PolicyWorkflow.CANCELLED, PolicyWorkflow.LAPSED, PolicyWorkflow.TERMINATED, PolicyWorkflow.EXPIRED,
                PolicyWorkflow.CLAIMED).contains(policy.getStatus())) {
            throw new IllegalArgumentException("Policy " + policy.getRef() + " is " + policy.getStatus()
                    + "; there is nothing left to collect.");
        }
        String channel = required(body, "channel", 3, 20).toUpperCase(Locale.ROOT);
        if (!CHANNELS.contains(channel)) {
            throw new IllegalArgumentException("Channel must be DEBIT_ORDER or MOBILE_WALLET.");
        }
        String institution = required(body, "institution", 2, 60);
        if (CollectionMandate.MOBILE_WALLET.equals(channel)) {
            institution = WALLETS.stream().filter(w -> w.equalsIgnoreCase(body.get("institution").asText().trim()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("A mobile wallet is "
                            + String.join(" or ", WALLETS) + "."));
        }
        String account = required(body, "accountNumber", 4, 30).replaceAll("\\s", "");
        if (!account.matches("[0-9A-Za-z+-]{4,30}")) {
            throw new IllegalArgumentException("The account or wallet number may hold only letters, digits, + and -.");
        }
        Integer debitDay = null;
        if (body.hasNonNull("debitDay") && !body.get("debitDay").asText().isBlank()) {
            try {
                debitDay = Integer.parseInt(body.get("debitDay").asText().trim());
            } catch (NumberFormatException ex) {
                debitDay = 0;
            }
            if (debitDay < 1 || debitDay > 28) {
                throw new IllegalArgumentException("The debit day must be a day of the month from 1 to 28.");
            }
        }
        if (CollectionMandate.DEBIT_ORDER.equals(channel) && debitDay == null) {
            throw new IllegalArgumentException("A debit order needs the debit day.");
        }
        String holder = optional(body, "accountHolder", 150);
        CollectionMandate mandate = create(policy, channel, institution, account, holder, debitDay,
                CollectionMandate.FROM_STAFF, user.getUsername());
        return view(mandate);
    }

    @Transactional
    public CollectionsViews.MandateView cancelMandate(String ref, JsonNode body) {
        AppUser user = user("MANAGE_FINANCE");
        CollectionMandate mandate = mandate(ref);
        if (!mandate.isActive()) {
            throw new ConflictException("Mandate " + ref + " is already cancelled.");
        }
        mandate.cancel(required(body, "reason", 10, 500), user.getUsername());
        return view(mandate);
    }

    // ── batches ──────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CollectionsViews.CollectionBatchView> batches() {
        reader();
        return em.createQuery("select b from CollectionBatch b order by b.id desc", CollectionBatch.class)
                .setMaxResults(100).getResultStream().map(b -> view(b, false, List.of())).toList();
    }

    @Transactional(readOnly = true)
    public CollectionsViews.CollectionBatchView batch(String ref) {
        reader();
        return view(batchByRef(ref), true, List.of());
    }

    /**
     * {collectionDate?}: one instruction for every due instalment of an active policy with a mandate, due by the
     * collection date plus the collection window, that is not already being collected. Nothing is sent yet.
     */
    @Transactional
    public CollectionsViews.CollectionBatchView prepare(JsonNode body) {
        AppUser user = user("MANAGE_FINANCE");
        LocalDate date = date(body, "collectionDate", LocalDate.now());
        if (date.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("The collection date cannot be in the past.");
        }
        LocalDate horizon = date.plusDays(windowDays);
        List<String> notes = new ArrayList<>();
        List<Object[]> candidates = new ArrayList<>();
        for (Instalment instalment : em.createQuery("select i from Instalment i where i.status = :due"
                        + " and i.dueDate <= :horizon order by i.dueDate, i.id", Instalment.class)
                .setParameter("due", Instalment.DUE).setParameter("horizon", horizon).getResultList()) {
            if (!em.createQuery("select c.id from CollectionInstruction c where c.instalmentRef = :ref"
                            + " and c.status in :live", Long.class)
                    .setParameter("ref", instalment.getRef()).setParameter("live", CollectionInstruction.LIVE)
                    .setMaxResults(1).getResultList().isEmpty()) {
                continue;
            }
            InsurancePolicy policy = policies.policy(instalment.getPolicyRef());
            if (!PolicyWorkflow.IN_COVER.contains(policy.getStatus())) {
                continue;
            }
            CollectionMandate mandate = activeMandate(policy, user.getUsername());
            if (mandate == null) {
                notes.add(instalment.getRef() + " (" + policy.getRef() + "): no debit-order or wallet mandate");
                continue;
            }
            BigDecimal outstanding = billing.outstandingOf(instalment);
            if (outstanding.compareTo(instalment.getBilledAmount()) != 0
                    || policy.getPremium() == null || outstanding.compareTo(policy.getPremium()) != 0) {
                notes.add(instalment.getRef() + " (" + policy.getRef() + "): " + Money.display(outstanding)
                        + " outstanding differs from the premium; collect it by hand");
                continue;
            }
            if (!policies.nextPeriod(policy).start().equals(instalment.getPeriodStart())) {
                notes.add(instalment.getRef() + " (" + policy.getRef() + "): not the policy's next premium period");
                continue;
            }
            candidates.add(new Object[] {instalment, mandate, outstanding});
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Nothing is due for collection by " + horizon + "."
                    + (notes.isEmpty() ? "" : " Left out: " + String.join("; ", notes) + "."));
        }
        CollectionBatch batch = new CollectionBatch(references.next("CRB-", 6, () -> 0L), date, rail.provider(),
                user.getUsername());
        em.persist(batch);
        BigDecimal total = BigDecimal.ZERO;
        for (Object[] c : candidates) {
            Instalment instalment = (Instalment) c[0];
            BigDecimal amount = ((BigDecimal) c[2]).setScale(2);
            LocalDate collectOn = instalment.getDueDate().isAfter(date) ? instalment.getDueDate() : date;
            em.persist(new CollectionInstruction(references.next("COL-", 6, () -> 0L), batch,
                    (CollectionMandate) c[1], instalment, amount, collectOn));
            total = total.add(amount);
        }
        batch.totals(candidates.size(), total);
        em.flush();
        return view(batch, true, notes);
    }

    /** A second person approves the batch; the one who prepared it cannot (NS-SH04-002 approved instructions). */
    @Transactional
    public CollectionsViews.CollectionBatchView approve(String ref) {
        AppUser user = user("APPROVE_JOURNAL");
        CollectionBatch batch = batchByRef(ref);
        if (!CollectionBatch.PREPARED.equals(batch.getStatus())) {
            throw new ConflictException("Batch " + ref + " is " + batch.getStatus().toLowerCase(Locale.ROOT)
                    + "; only a prepared batch can be approved.");
        }
        if (user.getUsername().equalsIgnoreCase(batch.getPreparedBy())) {
            throw new NoAuthorizationException("You prepared batch " + ref + "; someone else must approve it.");
        }
        batch.approve(user.getUsername());
        instructions(batch).forEach(i -> i.status(CollectionInstruction.APPROVED));
        return view(batch, true, List.of());
    }

    @Transactional
    public CollectionsViews.CollectionBatchView cancel(String ref, JsonNode body) {
        AppUser user = user("MANAGE_FINANCE");
        CollectionBatch batch = batchByRef(ref);
        if (!Set.of(CollectionBatch.PREPARED, CollectionBatch.APPROVED).contains(batch.getStatus())) {
            throw new ConflictException("Batch " + ref + " was already " + batch.getStatus().toLowerCase(Locale.ROOT)
                    + "; only a batch not yet submitted can be cancelled.");
        }
        batch.cancel(required(body, "reason", 10, 500) + " (" + user.getUsername() + ")");
        instructions(batch).forEach(i -> i.status(CollectionInstruction.CANCELLED));
        return view(batch, true, List.of());
    }

    /** Sends an approved batch to the rail. Sending again changes nothing. */
    @Transactional
    public CollectionsViews.CollectionBatchView submit(String ref) {
        AppUser user = user("MANAGE_FINANCE");
        CollectionBatch batch = batchByRef(ref);
        if (Set.of(CollectionBatch.SUBMITTED, CollectionBatch.COMPLETED).contains(batch.getStatus())) {
            return view(batch, true, List.of());
        }
        if (!CollectionBatch.APPROVED.equals(batch.getStatus())) {
            throw new ConflictException("Batch " + ref + " is " + batch.getStatus().toLowerCase(Locale.ROOT)
                    + "; it must be approved by a second person before it is sent.");
        }
        List<CollectionInstruction> instructions = instructions(batch);
        CollectionRail.Submission submission = rail.submit(batch, instructions);
        for (CollectionInstruction instruction : instructions) {
            instruction.submitted(submission.references().get(instruction.getRef()));
        }
        batch.submitted(submission.fileRef(), user.getUsername());
        return view(batch, true, List.of());
    }

    /**
     * Reads the rail's response for every instruction still waiting: {outcomes?: [{instructionRef, outcome, reason?}]}
     * (simulation overrides). Confirmed money is applied; failures leave the instalment owed.
     */
    @Transactional
    public CollectionsViews.CollectionBatchView bankResponse(String ref, JsonNode body) {
        AppUser user = user("MANAGE_FINANCE");
        CollectionBatch batch = batchByRef(ref);
        if (!CollectionBatch.SUBMITTED.equals(batch.getStatus())) {
            throw new ConflictException("Batch " + ref + " is " + batch.getStatus().toLowerCase(Locale.ROOT)
                    + "; only a submitted batch has a response to read.");
        }
        Map<String, String[]> requested = new HashMap<>();
        JsonNode outcomes = body == null ? null : body.get("outcomes");
        if (outcomes != null && outcomes.isArray()) {
            for (JsonNode o : outcomes) {
                requested.put(o.path("instructionRef").asText(""), new String[] {o.path("outcome").asText(null),
                        o.hasNonNull("reason") ? o.get("reason").asText() : null});
            }
        }
        for (CollectionInstruction instruction : instructions(batch)) {
            if (!CollectionInstruction.AWAITING_BANK.contains(instruction.getStatus())) {
                continue;
            }
            String[] override = requested.getOrDefault(instruction.getRef(), new String[] {null, null});
            CollectionRail.Answer answer = rail.answer(instruction, override[0], override[1]);
            switch (answer.status()) {
                case CollectionInstruction.CONFIRMED -> confirmed(instruction, answer, user.getUsername());
                case CollectionInstruction.FAILED -> instruction.answered(CollectionInstruction.FAILED, answer.code(),
                        answer.text() + "; the instalment stays owed");
                default -> instruction.answered(CollectionInstruction.PENDING, answer.code(), answer.text());
            }
        }
        em.flush();
        if (instructions(batch).stream().noneMatch(i -> CollectionInstruction.AWAITING_BANK.contains(i.getStatus()))) {
            batch.completed();
        }
        return view(batch, true, List.of());
    }

    /**
     * The bank took a confirmed collection back (simulated return file): {kind RETURNED|REVERSED, reason}. The receipt
     * is reversed as for any returned payment (instalment reopened, cover review, PaymentReturned); the bank's return
     * is a fact, so no second approval is asked.
     */
    @Transactional
    public CollectionsViews.InstructionView bankReturn(String ref, JsonNode body) {
        AppUser user = user("MANAGE_FINANCE");
        CollectionInstruction instruction = em.createQuery("select c from CollectionInstruction c join fetch c.batch"
                        + " where c.ref = :ref", CollectionInstruction.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Collection instruction " + ref + " not found"));
        if (!CollectionInstruction.CONFIRMED.equals(instruction.getStatus())) {
            throw new ConflictException("Instruction " + ref + " is " + instruction.getStatus().toLowerCase(Locale.ROOT)
                    + "; only a confirmed collection can be returned.");
        }
        if (instruction.getReceiptRef() == null) {
            throw new ConflictException("Instruction " + ref + " was never applied to the policy (its money is in"
                    + " suspense); refund or clear it from the suspense queue.");
        }
        String kind = required(body, "kind", 6, 20).toUpperCase(Locale.ROOT);
        if (!Set.of(ReceiptReversal.RETURNED, ReceiptReversal.REVERSED).contains(kind)) {
            throw new IllegalArgumentException("A bank return is RETURNED or REVERSED.");
        }
        String reason = required(body, "reason", 10, 400);
        CollectionsViews.ReversalView reversal = collections.reverseConfirmed(instruction.getReceiptRef(), kind,
                "Bank " + kind.toLowerCase(Locale.ROOT) + " " + instruction.getProviderReference() + ": " + reason,
                "bank:" + instruction.getBatch().getProvider() + " (recorded by " + user.getUsername() + ")");
        instruction.returned(RETURNED_STATUS.get(kind), reversal.ref(), reason);
        return view(instruction);
    }

    private static final Map<String, String> RETURNED_STATUS = Map.of(ReceiptReversal.RETURNED,
            CollectionInstruction.RETURNED, ReceiptReversal.REVERSED, CollectionInstruction.REVERSED);

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    /**
     * Applies a confirmed collection: the settled transaction, receipt and allocation (ReceiptConfirmed,
     * ReceiptAllocated, PremiumReceived), or suspense when the policy can no longer take this premium.
     */
    private void confirmed(CollectionInstruction instruction, CollectionRail.Answer answer, String actor) {
        InsurancePolicy policy = policies.policy(instruction.getPolicyRef());
        Instalment instalment = billing.byRef(instruction.getInstalmentRef());
        String problem = null;
        if (!PolicyWorkflow.IN_COVER.contains(policy.getStatus())) {
            problem = "policy is " + policy.getStatus();
        } else if (!Instalment.DUE.equals(instalment.getStatus())
                || billing.outstandingOf(instalment).compareTo(instruction.getAmount()) != 0) {
            problem = "instalment " + instalment.getRef() + " is " + instalment.getStatus().toLowerCase(Locale.ROOT);
        } else if (!policies.nextPeriod(policy).start().equals(instruction.getPeriodStart())) {
            problem = "the period is not the policy's next premium period";
        }
        String method = CollectionMandate.MOBILE_WALLET.equals(instruction.getChannel())
                ? instruction.getInstitution() : "Bank EFT";
        if (problem == null) {
            PaymentTransaction tx = settlement.settleCollected(policy.getRef(), rail.provider(), method,
                    instruction.getProviderReference(), instruction.getAmount(), instruction.getCurrency(),
                    instruction.getPeriodStart(), instruction.getPeriodEnd(), instalment.getPurpose(),
                    answer.code() + " " + answer.text(), "collection:" + actor);
            if (PaymentTransaction.SETTLED.equals(tx.getStatus())) {
                PremiumPayment payment = tx.getPremiumPaymentId() == null ? null
                        : em.find(PremiumPayment.class, tx.getPremiumPaymentId());
                instruction.answered(CollectionInstruction.CONFIRMED, answer.code(), answer.text());
                instruction.applied(payment == null ? null : payment.getReceiptRef(), tx.getId());
                return;
            }
            problem = tx.getFailureReason();
        }
        // Money the bank collected that the policy cannot take: never lost, held in suspense (NS-SH04-003).
        String reason = "Collected by " + instruction.getRef() + " but not applied: " + problem;
        InboundMessage held = InboundMessage.heldInSuspense(rail.provider(), instruction.getProviderReference(),
                "COLLECTION", policy.getRef(), instruction.getAmount(), reason, "collection:" + actor);
        em.persist(held);
        Map<String, Object> confirmed = new LinkedHashMap<>();
        confirmed.put("receiptRef", rail.provider() + ":" + instruction.getProviderReference());
        confirmed.put("provider", rail.provider());
        confirmed.put("providerRef", instruction.getProviderReference());
        confirmed.put("policyRef", policy.getRef());
        confirmed.put("amount", instruction.getAmount().toPlainString());
        confirmed.put("currency", instruction.getCurrency());
        confirmed.put("method", method);
        confirmed.put("source", "COLLECTION");
        outbox.publish(Events.RECEIPT_CONFIRMED, instruction.getProviderReference(), confirmed, actor);
        Map<String, Object> unmatched = new LinkedHashMap<>();
        unmatched.put("provider", rail.provider());
        unmatched.put("providerRef", instruction.getProviderReference());
        unmatched.put("policyRef", policy.getRef());
        unmatched.put("amount", instruction.getAmount().toPlainString());
        unmatched.put("reason", reason.length() <= 300 ? reason : reason.substring(0, 300));
        outbox.publish(Events.UNMATCHED_RECEIPT, instruction.getProviderReference(), unmatched, actor);
        instruction.answered(CollectionInstruction.CONFIRMED, answer.code(), answer.text() + "; held in suspense: "
                + problem);
    }

    /** The policy's active mandate, or one made from the debit-order or wallet mandate on its application. */
    private CollectionMandate activeMandate(InsurancePolicy policy, String actor) {
        CollectionMandate active = em.createQuery("select m from CollectionMandate m where m.activePolicyRef = :ref",
                        CollectionMandate.class)
                .setParameter("ref", policy.getRef()).getResultStream().findFirst().orElse(null);
        if (active != null || policy.getApplicationRef() == null) {
            return active;
        }
        InsuranceApplication app = em.createQuery("select a from InsuranceApplication a where a.ref = :ref",
                        InsuranceApplication.class)
                .setParameter("ref", policy.getApplicationRef()).getResultStream().findFirst().orElse(null);
        if (app == null || app.getMandateAccount() == null || app.getMandateAccount().isBlank()
                || !CHANNELS.contains(app.getMandateMethod() == null ? "" : app.getMandateMethod())) {
            return null;
        }
        String institution = app.getMandateInstitution() == null || app.getMandateInstitution().isBlank()
                ? (CollectionMandate.MOBILE_WALLET.equals(app.getMandateMethod()) ? WALLETS.get(0) : "Bank")
                : app.getMandateInstitution();
        if (CollectionMandate.DEBIT_ORDER.equals(app.getMandateMethod()) && app.getMandateDebitDay() == null) {
            return null;
        }
        return create(policy, app.getMandateMethod(), institution, app.getMandateAccount().replaceAll("\\s", ""),
                app.getPayerName(), app.getMandateDebitDay(), CollectionMandate.FROM_APPLICATION, actor);
    }

    private CollectionMandate create(InsurancePolicy policy, String channel, String institution, String account,
            String holder, Integer debitDay, String source, String actor) {
        String ref = references.next("MDT-", 6, () -> 0L);
        em.createQuery("select m from CollectionMandate m where m.activePolicyRef = :ref", CollectionMandate.class)
                .setParameter("ref", policy.getRef()).getResultStream()
                .forEach(old -> old.cancel("Replaced by " + ref, actor));
        em.flush();
        CollectionMandate mandate = new CollectionMandate(ref, policy.getRef(), channel, institution, account,
                holder == null ? policy.getPayerName() : holder, debitDay, source, actor);
        em.persist(mandate);
        em.flush();
        return mandate;
    }

    private List<CollectionInstruction> instructions(CollectionBatch batch) {
        return em.createQuery("select c from CollectionInstruction c join fetch c.mandate where c.batch = :batch"
                + " order by c.id", CollectionInstruction.class).setParameter("batch", batch).getResultList();
    }

    private CollectionBatch batchByRef(String ref) {
        return em.createQuery("select b from CollectionBatch b where b.ref = :ref", CollectionBatch.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Collection batch " + ref + " not found"));
    }

    private CollectionMandate mandate(String ref) {
        return em.createQuery("select m from CollectionMandate m where m.ref = :ref", CollectionMandate.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Mandate " + ref + " not found"));
    }

    private CollectionsViews.CollectionBatchView view(CollectionBatch b, boolean withInstructions, List<String> notes) {
        List<CollectionInstruction> rows = instructions(b);
        Map<String, Long> byStatus = new TreeMap<>();
        rows.forEach(i -> byStatus.merge(i.getStatus(), 1L, Long::sum));
        return new CollectionsViews.CollectionBatchView(b.getRef(), b.getCollectionDate(), b.getProvider(),
                b.getStatus(), b.getInstructionCount(), b.getTotalAmount(), b.getFileRef(), b.getPreparedBy(),
                b.getPreparedAt(), b.getApprovedBy(), b.getApprovedAt(), b.getSubmittedBy(), b.getSubmittedAt(),
                b.getCompletedAt(), b.getNote(), byStatus,
                withInstructions ? rows.stream().map(CollectionRuns::view).toList() : List.of(), notes);
    }

    static CollectionsViews.InstructionView view(CollectionInstruction i) {
        return new CollectionsViews.InstructionView(i.getRef(), i.getBatch().getRef(), i.getInstalmentRef(),
                i.getPolicyRef(), i.getChannel(), i.getInstitution(), i.getAccountMasked(), i.getAmount(),
                i.getCurrency(), i.getPeriodStart(), i.getPeriodEnd(), i.getCollectionDate(), i.getStatus(),
                i.getProviderReference(), i.getResultCode(), i.getResultText(), i.getReceiptRef(), i.getTransactionId(),
                i.getReversalRef(), i.getSubmittedAt(), i.getRespondedAt(), i.getReturnedAt());
    }

    static CollectionsViews.MandateView view(CollectionMandate m) {
        return new CollectionsViews.MandateView(m.getRef(), m.getPolicyRef(), m.getChannel(), m.getInstitution(),
                m.maskedAccount(), m.getAccountHolder(), m.getDebitDay(), m.getStatus(), m.getSource(),
                m.getCreatedBy(), m.getCreatedAt(), m.getCancelledBy(), m.getCancelledAt(), m.getCancelReason());
    }

    private void reader() {
        AppUser user = security.authenticatedUser();
        if (!user.hasPermissionTo("READ_FINANCE") && !user.hasPermissionTo("READ_POLICY")) {
            user.validateHasPermissionTo("READ_FINANCE");
        }
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    private static LocalDate date(JsonNode body, String field, LocalDate fallback) {
        if (body == null || !body.hasNonNull(field) || body.get(field).asText().isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(body.get(field).asText().trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(field + " must be a date like 2026-12-01.");
        }
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

    private static String optional(JsonNode body, String field, int max) {
        if (body == null || !body.hasNonNull(field) || body.get(field).asText().isBlank()) {
            return null;
        }
        String value = body.get(field).asText().trim();
        if (value.length() > max) {
            throw new IllegalArgumentException(field + " is too long (at most " + max + " characters).");
        }
        return value;
    }
}
