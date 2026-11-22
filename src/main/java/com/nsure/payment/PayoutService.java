package com.nsure.payment;

import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.Money;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.portfolio.ReferenceNumbers;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outgoing benefit payments (NS-SH05-007, §09 payment state machine, NS-TXN-003, AT-SH05-02, AT-SH05-03).
 *
 * <ul>
 *   <li>An instruction is created and approved inside the transaction that authorises the liability (claim approval,
 *       Care Cash payment), with a unique reference and a unique idempotency key per attempt.</li>
 *   <li>Submitting hands it to the payout rail (the {@link SimulatedBankRail} until a provider is chosen, D12). It is
 *       then in flight: nothing is paid, posted or reinsured until the rail confirms settlement.</li>
 *   <li>Every rail callback is kept by its event id; a repeated callback is recognised and changes nothing. A callback
 *       that contradicts the instruction's state (e.g. "rejected" after "settled") changes nothing either: it opens an
 *       exception for a person to investigate, so no false settled status can appear.</li>
 *   <li>No answer within {@code nsure.payouts.unknown-after-minutes} makes the instruction UNKNOWN: it is reconciled
 *       with the bank (status enquiry) before anything is sent again.</li>
 *   <li>A rejection, a return of a settled payment, an unknown outcome or a contradicting callback puts the
 *       instruction in the payout exception queue until it is replaced, reconciled or resolved with a note.</li>
 * </ul>
 */
@Service
public class PayoutService {

    public static final String RAIL_ACTOR = "Payout rail (simulated)";

    /** What a benefit asks to be paid. */
    public record Request(String sourceType, String sourceRef, String policyRef, String payeeName, String payoutMethod,
            String accountRef, BigDecimal amount, String currency) {
    }

    private final EntityManager em;
    private final SimulatedBankRail rail;
    private final ReferenceNumbers numbers;
    private final ObjectProvider<PayoutOutcomeHandler> handlers;
    private final Outbox outbox;
    private final EvidenceChain evidence;
    private final PlatformSecurityContext security;
    private final TransactionTemplate tx;
    private final int unknownAfterMinutes;

    public PayoutService(EntityManager em, SimulatedBankRail rail, ReferenceNumbers numbers,
            ObjectProvider<PayoutOutcomeHandler> handlers, Outbox outbox, EvidenceChain evidence,
            PlatformSecurityContext security,
            org.springframework.transaction.PlatformTransactionManager transactions,
            @Value("${nsure.payouts.unknown-after-minutes:60}") int unknownAfterMinutes) {
        this.em = em;
        this.rail = rail;
        this.numbers = numbers;
        this.handlers = handlers;
        this.outbox = outbox;
        this.evidence = evidence;
        this.security = security;
        this.tx = new TransactionTemplate(transactions);
        this.unknownAfterMinutes = unknownAfterMinutes;
    }

    // ── creating and submitting (called by the benefit's own transaction) ─────────────────────────

    /**
     * Creates the next instruction for a benefit and approves it in the caller's transaction (NS-TXN-003): the
     * approval of the liability is the approval of the instruction, recorded with the approver. Refused while another
     * instruction for the same benefit is still live; the unique open key backs this up against a race.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PayoutInstruction authorise(Request request, String approver) {
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new IllegalArgumentException("A payout instruction needs an amount greater than zero.");
        }
        List<PayoutInstruction> earlier = of(request.sourceType(), request.sourceRef());
        PayoutInstruction live = earlier.stream().filter(i -> !PayoutInstruction.FINAL.contains(i.getStatus()))
                .findFirst().orElse(null);
        if (live != null) {
            throw new ConflictException(live.getRef() + " is still " + label(live.getStatus()) + " for "
                    + request.sourceRef() + "; finish or cancel it before creating another instruction.");
        }
        PayoutInstruction previous = earlier.isEmpty() ? null : earlier.get(0);
        int attempt = previous == null ? 1 : previous.getAttemptNo() + 1;
        PayoutInstruction instruction = new PayoutInstruction(numbers.next("PI-", 6,
                        () -> numbers.highest("PayoutInstruction", "PI-")),
                request.sourceType(), request.sourceRef(), request.policyRef(), attempt, request.payeeName(),
                request.payoutMethod(), request.accountRef(), request.amount(),
                request.currency() == null ? "BWP" : request.currency(), previous == null ? null : previous.getRef(),
                approver);
        em.persist(instruction);
        step(instruction, null, PayoutInstruction.CREATED, "Instruction for " + Money.display(instruction.getAmount())
                + " to " + instruction.getPayeeName() + (previous == null ? "" : " · replaces " + previous.getRef()),
                approver);
        instruction.approve(approver);
        step(instruction, PayoutInstruction.CREATED, PayoutInstruction.APPROVED, "Approved with the decision on "
                + request.sourceRef(), approver);
        if (previous != null && previous.isExceptionOpen()) {
            previous.resolveException("Replaced by " + instruction.getRef(), approver);
        }
        em.flush();
        return instruction;
    }

    /** Hands an approved instruction to the rail; the bank acknowledges it and it waits there (PENDING). */
    @Transactional(propagation = Propagation.MANDATORY)
    public PayoutInstruction submit(PayoutInstruction instruction, String actor) {
        lock(instruction);
        if (!PayoutInstruction.APPROVED.equals(instruction.getStatus())) {
            throw new ConflictException(instruction.getRef() + " is " + label(instruction.getStatus())
                    + "; only an approved instruction is submitted.");
        }
        String providerReference = rail.accept(instruction);
        instruction.submitted(rail.provider(), providerReference, actor);
        step(instruction, PayoutInstruction.APPROVED, PayoutInstruction.SUBMITTED, "Sent to " + rail.provider()
                + " as " + providerReference, actor);
        instruction.moveTo(PayoutInstruction.PENDING);
        step(instruction, PayoutInstruction.SUBMITTED, PayoutInstruction.PENDING,
                "Accepted by the bank for processing; awaiting settlement", RAIL_ACTOR);
        return instruction;
    }

    /** Cancels an instruction not yet sent (the decision behind it was set aside). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelUnsent(String sourceType, String sourceRef, String reason, String actor) {
        for (PayoutInstruction instruction : of(sourceType, sourceRef)) {
            if (Set.of(PayoutInstruction.CREATED, PayoutInstruction.APPROVED).contains(instruction.getStatus())) {
                lock(instruction);
                String before = instruction.getStatus();
                instruction.moveTo(PayoutInstruction.CANCELLED);
                step(instruction, before, PayoutInstruction.CANCELLED, reason, actor);
            }
        }
    }

    /** The instruction a manual confirmation or failure names: by our reference or the bank's. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PayoutInstruction> byReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        return em.createQuery("select i from PayoutInstruction i where i.ref = :r or i.providerReference = :r",
                PayoutInstruction.class).setParameter("r", reference.trim()).getResultStream().findFirst();
    }

    /**
     * A person records the bank's confirmation (statement line, phone call). Same effect and the same exactly-once
     * guard as a rail callback, without notifying the benefit, which records its own side. Refused once settled.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settleManually(PayoutInstruction instruction, String actor) {
        lock(instruction);
        if (PayoutInstruction.SETTLED.equals(instruction.getStatus())) {
            throw new ConflictException(instruction.getRef() + " is already settled.");
        }
        if (!PayoutInstruction.IN_FLIGHT.contains(instruction.getStatus())) {
            throw new ConflictException(instruction.getRef() + " is " + label(instruction.getStatus())
                    + "; there is nothing in flight to confirm.");
        }
        record(instruction, "MANUAL-SETTLED-" + instruction.getRef(), "SETTLED", null, "Confirmed by " + actor,
                PayoutRecords.Callback.APPLIED, "Recorded by a person");
        settle(instruction, "Settlement confirmed by " + actor, actor);
    }

    /** A person records the bank's failure notice. Refused while the outcome is unknown: reconcile first. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void failManually(PayoutInstruction instruction, String reason, String actor) {
        lock(instruction);
        if (PayoutInstruction.UNKNOWN.equals(instruction.getStatus())) {
            throw new IllegalArgumentException(instruction.getRef() + " has an unknown outcome at the bank. Reconcile"
                    + " it with the bank first; it may already have been paid.");
        }
        if (!PayoutInstruction.IN_FLIGHT.contains(instruction.getStatus())) {
            throw new ConflictException(instruction.getRef() + " is " + label(instruction.getStatus())
                    + "; there is nothing in flight to fail.");
        }
        record(instruction, "MANUAL-FAILED-" + instruction.getRef(), "REJECTED", null, reason,
                PayoutRecords.Callback.APPLIED, "Recorded by a person");
        fail(instruction, "MANUAL", reason, actor);
    }

    // ── the rail's answers ────────────────────────────────────────────────────────────────────────

    /** The result of a callback: what happened to the instruction because of it. */
    public record CallbackResult(String eventId, String instructionRef, String result, String status, String note) {
    }

    /** The rail's callback as it arrives over the API (an integration credential sends it). */
    @Transactional
    public CallbackResult providerCallback(String eventId, String providerReference, String outcome, String reasonCode,
            String reasonText) {
        user("MANAGE_INTEGRATION");
        return callback(eventId, providerReference, outcome, reasonCode, reasonText);
    }

    /**
     * A callback from the rail: {@code outcome} SETTLED, REJECTED or RETURNED. Exactly-once (AT-SH05-02): the event id
     * is stored before any effect and a repeat is answered DUPLICATE with no change; the instruction row is locked so
     * concurrent deliveries are taken one at a time.
     */
    @Transactional
    public CallbackResult callback(String eventId, String providerReference, String outcome, String reasonCode,
            String reasonText) {
        String event = required(eventId, "eventId");
        String kind = required(outcome, "outcome").toUpperCase(Locale.ROOT);
        if (!Set.of("SETTLED", "REJECTED", "RETURNED").contains(kind)) {
            throw new IllegalArgumentException("outcome must be SETTLED, REJECTED or RETURNED.");
        }
        PayoutInstruction instruction = em.createQuery("select i from PayoutInstruction i where i.providerReference = :r",
                        PayoutInstruction.class).setParameter("r", required(providerReference, "providerReference"))
                .getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("No payout instruction was sent as " + providerReference));
        lock(instruction);
        PayoutRecords.Callback seen = em.createQuery("select c from PayoutCallback c where c.eventId = :e",
                PayoutRecords.Callback.class).setParameter("e", event).getResultStream().findFirst().orElse(null);
        if (seen != null) {
            return new CallbackResult(event, instruction.getRef(), PayoutRecords.Callback.DUPLICATE,
                    instruction.getStatus(), "Already received at " + seen.getReceivedAt().withNano(0)
                            + "; no second effect.");
        }
        String status = instruction.getStatus();
        String text = reasonText == null || reasonText.isBlank() ? null : clip(reasonText.trim(), 300);
        boolean inFlight = PayoutInstruction.IN_FLIGHT.contains(status);
        String result;
        String note;
        if ("SETTLED".equals(kind) && inFlight) {
            settle(instruction, "Settled by the bank", RAIL_ACTOR);
            notify(instruction, h -> h.settled(instruction, RAIL_ACTOR));
            result = PayoutRecords.Callback.APPLIED;
            note = "Settled";
        } else if ("REJECTED".equals(kind) && inFlight) {
            String reason = (reasonCode == null ? "" : reasonCode + " ") + (text == null ? "Rejected by the bank" : text);
            fail(instruction, reasonCode, reason.trim(), RAIL_ACTOR);
            notify(instruction, h -> h.failed(instruction, reason.trim(), RAIL_ACTOR));
            result = PayoutRecords.Callback.APPLIED;
            note = "Failed";
        } else if ("RETURNED".equals(kind) && PayoutInstruction.SETTLED.equals(status)) {
            String reason = (reasonCode == null ? "" : reasonCode + " ") + (text == null ? "Returned by the bank" : text);
            instruction.failure(reasonCode, reason.trim());
            instruction.moveTo(PayoutInstruction.RETURNED);
            instruction.openException("Returned after settlement: " + reason.trim() + ". The benefit is unpaid again"
                    + " and needs a new approved instruction.");
            step(instruction, status, PayoutInstruction.RETURNED, reason.trim(), RAIL_ACTOR);
            notify(instruction, h -> h.returned(instruction, reason.trim(), RAIL_ACTOR));
            publish(Events.PAYOUT_RETURNED, instruction, reason.trim(), RAIL_ACTOR);
            result = PayoutRecords.Callback.APPLIED;
            note = "Returned";
        } else if (("SETTLED".equals(kind) && PayoutInstruction.SETTLED.equals(status))
                || ("REJECTED".equals(kind) && PayoutInstruction.FAILED.equals(status))
                || ("RETURNED".equals(kind) && PayoutInstruction.RETURNED.equals(status))) {
            result = PayoutRecords.Callback.IGNORED;
            note = "Already " + label(status) + " (a new event id for the same outcome); no second effect.";
        } else {
            // The bank says something the instruction's state cannot accept: a person investigates; nothing moves.
            result = PayoutRecords.Callback.CONFLICT;
            note = "Bank reported " + kind.toLowerCase(Locale.ROOT) + " but the instruction is " + label(status);
            instruction.openException(note + ". Nothing was changed; investigate with the bank.");
            step(instruction, status, status, note, RAIL_ACTOR);
        }
        record(instruction, event, kind, reasonCode, text, result, note);
        return new CallbackResult(event, instruction.getRef(), result, instruction.getStatus(), note);
    }

    /** No answer from the bank in time: the outcome is unknown and must be reconciled before any retry. */
    @Transactional
    public PayoutInstruction markUnknown(String ref, String actor) {
        PayoutInstruction instruction = instruction(ref);
        lock(instruction);
        if (!Set.of(PayoutInstruction.SUBMITTED, PayoutInstruction.PENDING).contains(instruction.getStatus())) {
            throw new ConflictException(ref + " is " + label(instruction.getStatus()) + ", not waiting for the bank.");
        }
        String before = instruction.getStatus();
        instruction.moveTo(PayoutInstruction.UNKNOWN);
        instruction.openException("No answer from the bank within " + unknownAfterMinutes + " minutes. Reconcile with"
                + " the bank before sending anything again; it may already have been paid.");
        step(instruction, before, PayoutInstruction.UNKNOWN, "No answer from the bank in time", actor);
        return instruction;
    }

    /**
     * Status enquiry to the bank (reconciliation of an UNKNOWN or still-pending instruction). The bank's answer is
     * applied like a callback; a payment the bank never received is failed, so it can safely be sent again.
     */
    @Transactional
    public CallbackResult reconcile(String ref) {
        AppUser user = user("SETTLE_CLAIM");
        PayoutInstruction instruction = instruction(ref);
        if (!PayoutInstruction.IN_FLIGHT.contains(instruction.getStatus())) {
            throw new ConflictException(ref + " is " + label(instruction.getStatus()) + "; there is nothing to reconcile.");
        }
        Optional<PayoutRecords.BankPayment> bank = rail.enquire(instruction.getProviderReference());
        String eventId = "ENQ-" + instruction.getRef() + "-" + UUID.randomUUID().toString().substring(0, 8);
        if (bank.isEmpty()) {
            lock(instruction);
            String reason = "Not received by the bank (status enquiry by " + user.getUsername() + ")";
            record(instruction, eventId, "REJECTED", "NOTFOUND", reason, PayoutRecords.Callback.APPLIED, "Reconciled");
            fail(instruction, "NOTFOUND", reason, user.getUsername());
            notify(instruction, h -> h.failed(instruction, reason, user.getUsername()));
            return new CallbackResult(eventId, ref, PayoutRecords.Callback.APPLIED, instruction.getStatus(), reason);
        }
        PayoutRecords.BankPayment payment = bank.get();
        return switch (payment.getStatus()) {
            case PayoutRecords.BankPayment.SETTLED -> callback(eventId, payment.getProviderReference(), "SETTLED",
                    null, "Confirmed by status enquiry");
            case PayoutRecords.BankPayment.REJECTED, PayoutRecords.BankPayment.RETURNED -> callback(eventId,
                    payment.getProviderReference(), "REJECTED", payment.getReasonCode(), payment.getReasonText());
            default -> {
                lock(instruction);
                if (PayoutInstruction.UNKNOWN.equals(instruction.getStatus())) {
                    instruction.moveTo(PayoutInstruction.PENDING);
                    instruction.resolveException("Bank confirms it is still processing the payment", user.getUsername());
                    step(instruction, PayoutInstruction.UNKNOWN, PayoutInstruction.PENDING,
                            "Status enquiry: still processing at the bank", user.getUsername());
                }
                yield new CallbackResult(eventId, ref, PayoutRecords.Callback.IGNORED, instruction.getStatus(),
                        "Still processing at the bank");
            }
        };
    }

    /** Closes an exception with a note when no resend is due (e.g. the claim was reopened or the payee paid otherwise). */
    @Transactional
    public PayoutInstruction resolve(String ref, String note) {
        AppUser user = user("SETTLE_CLAIM");
        PayoutInstruction instruction = instruction(ref);
        lock(instruction);
        if (!instruction.isExceptionOpen()) {
            throw new ConflictException(ref + " has no open exception.");
        }
        if (PayoutInstruction.UNKNOWN.equals(instruction.getStatus())) {
            throw new IllegalArgumentException(ref + " has an unknown outcome. Reconcile it with the bank instead.");
        }
        String text = required(note, "note");
        if (text.length() < 5) {
            throw new IllegalArgumentException("Say how the exception was resolved.");
        }
        instruction.resolveException(clip(text, 300), user.getUsername());
        step(instruction, instruction.getStatus(), instruction.getStatus(), "Exception resolved: " + text,
                user.getUsername());
        evidence.record(user.getUsername(), "Resolved payout exception", "PayoutInstruction", ref, text);
        return instruction;
    }

    /** Instructions still waiting for the bank past the window become UNKNOWN (every few minutes). */
    @Scheduled(fixedDelayString = "${nsure.payouts.sweep-ms:300000}", initialDelayString = "${nsure.payouts.sweep-ms:300000}")
    public void sweepUnanswered() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(unknownAfterMinutes);
        List<String> late = tx.execute(s -> em.createQuery("select i.ref from PayoutInstruction i where i.status in"
                        + " (:states) and i.submittedAt < :cutoff", String.class)
                .setParameter("states", List.of(PayoutInstruction.SUBMITTED, PayoutInstruction.PENDING))
                .setParameter("cutoff", cutoff).getResultList());
        for (String ref : late == null ? List.<String>of() : late) {
            try {
                tx.executeWithoutResult(s -> markUnknown(ref, RAIL_ACTOR));
            } catch (RuntimeException ex) {
                // Answered meanwhile; nothing to do.
            }
        }
    }

    // ── the simulated bank console (demo) ──────────────────────────────────────────────────────────

    /**
     * SIMULATED bank decisions for a demo: SETTLE, REJECT, RETURN (a settled payment comes back), SETTLE_SILENTLY (the
     * bank pays but its callback is lost), TIMEOUT (no answer in time: UNKNOWN) and REPEAT (the bank re-sends its last
     * callback, which must have no second effect).
     */
    @Transactional
    public CallbackResult simulate(String ref, String action, String reasonCode, String reasonText) {
        AppUser user = user("SETTLE_CLAIM");
        PayoutInstruction instruction = instruction(ref);
        String what = required(action, "action").toUpperCase(Locale.ROOT);
        String provider = instruction.getProviderReference();
        if (provider == null) {
            throw new IllegalArgumentException(ref + " has not been sent to the bank yet.");
        }
        PayoutRecords.BankPayment bank = rail.enquire(provider)
                .orElseThrow(() -> new NotFoundException("The simulated bank has no payment " + provider));
        String eventId = "SB-EVT-" + UUID.randomUUID().toString().substring(0, 12);
        evidence.record(user.getUsername(), "Simulated bank: " + what, "PayoutInstruction", ref,
                "Demo payout rail decision" + (reasonCode == null ? "" : " " + reasonCode));
        switch (what) {
            case "SETTLE" -> {
                requireBank(bank, PayoutRecords.BankPayment.ACCEPTED, "settle");
                rail.decide(provider, PayoutRecords.BankPayment.SETTLED, null, "Paid to the payee", eventId);
                return callback(eventId, provider, "SETTLED", null, "Paid to the payee");
            }
            case "SETTLE_SILENTLY" -> {
                requireBank(bank, PayoutRecords.BankPayment.ACCEPTED, "settle");
                rail.decide(provider, PayoutRecords.BankPayment.SETTLED, null, "Paid to the payee", eventId);
                return new CallbackResult(eventId, ref, "NOT_DELIVERED", instruction.getStatus(),
                        "The bank paid, but its callback was lost; the instruction still waits for an answer.");
            }
            case "REJECT" -> {
                requireBank(bank, PayoutRecords.BankPayment.ACCEPTED, "reject");
                String code = reasonCode == null || reasonCode.isBlank() ? "AC01" : reasonCode.trim();
                String text = reasonText == null || reasonText.isBlank() ? "Incorrect account number" : reasonText.trim();
                rail.decide(provider, PayoutRecords.BankPayment.REJECTED, code, text, eventId);
                return callback(eventId, provider, "REJECTED", code, text);
            }
            case "RETURN" -> {
                requireBank(bank, PayoutRecords.BankPayment.SETTLED, "return");
                String code = reasonCode == null || reasonCode.isBlank() ? "AC04" : reasonCode.trim();
                String text = reasonText == null || reasonText.isBlank() ? "Account closed: funds returned"
                        : reasonText.trim();
                rail.decide(provider, PayoutRecords.BankPayment.RETURNED, code, text, eventId);
                return callback(eventId, provider, "RETURNED", code, text);
            }
            case "TIMEOUT" -> {
                PayoutInstruction marked = markUnknown(ref, RAIL_ACTOR);
                return new CallbackResult(null, ref, "TIMEOUT", marked.getStatus(), "No answer from the bank in time");
            }
            case "REPEAT" -> {
                if (bank.getLastEventId() == null) {
                    throw new IllegalArgumentException("The bank has not answered " + ref + " yet, so there is nothing to"
                            + " repeat.");
                }
                String outcome = switch (bank.getStatus()) {
                    case PayoutRecords.BankPayment.SETTLED -> "SETTLED";
                    case PayoutRecords.BankPayment.RETURNED -> "RETURNED";
                    default -> "REJECTED";
                };
                return callback(bank.getLastEventId(), provider, outcome, bank.getReasonCode(), bank.getReasonText());
            }
            default -> throw new IllegalArgumentException(
                    "action must be SETTLE, REJECT, RETURN, SETTLE_SILENTLY, TIMEOUT or REPEAT.");
        }
    }

    // ── reading ──────────────────────────────────────────────────────────────────────────────────

    /** A benefit's instructions, newest first. */
    public List<PayoutInstruction> of(String sourceType, String sourceRef) {
        return em.createQuery("select i from PayoutInstruction i where i.sourceType = :t and i.sourceRef = :r"
                        + " order by i.attemptNo desc, i.id desc", PayoutInstruction.class)
                .setParameter("t", sourceType).setParameter("r", sourceRef).getResultList();
    }

    @Transactional(readOnly = true)
    public List<PayoutViews.InstructionView> instructions(String sourceType, String sourceRef) {
        user("READ_CLAIM");
        return of(sourceType == null ? "" : sourceType.toUpperCase(Locale.ROOT), sourceRef).stream()
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public PayoutViews.InstructionView instructionView(String ref) {
        user("READ_CLAIM");
        return view(instruction(ref));
    }

    /** The payout exception queue (AT-SH05-03): rejected, returned, unknown or contradicted instructions. */
    @Transactional(readOnly = true)
    public List<PayoutViews.InstructionView> exceptions() {
        user("READ_CLAIM");
        return em.createQuery("select i from PayoutInstruction i where i.exceptionState = :open order by i.id desc",
                PayoutInstruction.class).setParameter("open", PayoutInstruction.EXCEPTION_OPEN).getResultList()
                .stream().map(this::view).toList();
    }

    public PayoutViews.InstructionView view(PayoutInstruction i) {
        List<PayoutViews.StepView> steps = em.createQuery("select s from PayoutStep s where s.instructionRef = :r"
                        + " order by s.id", PayoutRecords.Step.class).setParameter("r", i.getRef()).getResultList()
                .stream().map(s -> new PayoutViews.StepView(s.getFromStatus(), s.getToStatus(), s.getNote(),
                        s.getActor(), s.getOccurredAt())).toList();
        List<PayoutViews.CallbackView> callbacks = em.createQuery("select c from PayoutCallback c where"
                        + " c.instructionRef = :r order by c.id", PayoutRecords.Callback.class)
                .setParameter("r", i.getRef()).getResultList().stream()
                .map(c -> new PayoutViews.CallbackView(c.getEventId(), c.getOutcome(), c.getReasonCode(),
                        c.getReasonText(), c.getResult(), c.getNote(), c.getReceivedAt())).toList();
        String bank = rail.enquire(i.getProviderReference()).map(PayoutRecords.BankPayment::getStatus).orElse(null);
        List<String> actions = new ArrayList<>();
        if (PayoutInstruction.IN_FLIGHT.contains(i.getStatus())) {
            actions.add("reconcile");
        }
        if (i.isExceptionOpen() && !PayoutInstruction.UNKNOWN.equals(i.getStatus())) {
            actions.add("resolve");
        }
        List<String> simulator = new ArrayList<>();
        if (PayoutRecords.BankPayment.ACCEPTED.equals(bank)) {
            simulator.addAll(List.of("SETTLE", "REJECT", "SETTLE_SILENTLY"));
            if (Set.of(PayoutInstruction.SUBMITTED, PayoutInstruction.PENDING).contains(i.getStatus())) {
                simulator.add("TIMEOUT");
            }
        }
        if (PayoutRecords.BankPayment.SETTLED.equals(bank)) {
            simulator.add("RETURN");
            if (Set.of(PayoutInstruction.SUBMITTED, PayoutInstruction.PENDING).contains(i.getStatus())) {
                simulator.add("TIMEOUT");
            }
        }
        if (bank != null && !PayoutRecords.BankPayment.ACCEPTED.equals(bank)) {
            simulator.add("REPEAT");
        }
        return new PayoutViews.InstructionView(i.getRef(), i.getSourceType(), i.getSourceRef(), i.getPolicyRef(),
                i.getAttemptNo(), i.getIdempotencyKey(), i.getPayeeName(), i.getPayoutMethod(), mask(i.getAccountRef()),
                i.getAmount(), i.getCurrency(), i.getStatus(), i.getProvider(), i.getProviderReference(),
                i.getReplacesRef(), i.getCreatedBy(), i.getCreatedAt(), i.getApprovedBy(), i.getApprovedAt(),
                i.getSubmittedBy(), i.getSubmittedAt(), i.getOutcomeAt(), i.getFailureCode(), i.getFailureReason(),
                i.getExceptionState(), i.getExceptionNote(), i.getExceptionResolvedBy(), i.getExceptionResolvedAt(),
                bank, steps, callbacks, actions, simulator);
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    private void settle(PayoutInstruction instruction, String note, String actor) {
        String before = instruction.getStatus();
        instruction.moveTo(PayoutInstruction.SETTLED);
        if (instruction.isExceptionOpen()) {
            instruction.resolveException("Settled", actor);
        }
        step(instruction, before, PayoutInstruction.SETTLED, note, actor);
    }

    private void fail(PayoutInstruction instruction, String code, String reason, String actor) {
        String before = instruction.getStatus();
        instruction.failure(code, clip(reason, 500));
        instruction.moveTo(PayoutInstruction.FAILED);
        instruction.openException("Payment failed: " + reason + ". The benefit stays unpaid until a corrected"
                + " instruction is approved and sent.");
        step(instruction, before, PayoutInstruction.FAILED, reason, actor);
        publish(Events.PAYOUT_FAILED, instruction, reason, actor);
    }

    private void record(PayoutInstruction instruction, String eventId, String outcome, String code, String text,
            String result, String note) {
        em.persist(new PayoutRecords.Callback(eventId, instruction.getRef(), instruction.getProviderReference(),
                outcome, code, text == null ? null : clip(text, 300), result, note == null ? null : clip(note, 300)));
    }

    private void publish(String type, PayoutInstruction instruction, String reason, String actor) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("instructionRef", instruction.getRef());
        data.put("sourceType", instruction.getSourceType());
        data.put("sourceRef", instruction.getSourceRef());
        data.put("policyRef", instruction.getPolicyRef() == null ? "" : instruction.getPolicyRef());
        data.put("amount", instruction.getAmount().toPlainString());
        data.put("providerReference", instruction.getProviderReference() == null ? "" : instruction.getProviderReference());
        data.put("reason", reason == null ? "" : clip(reason, 200));
        outbox.publish(type, instruction.getRef(), data, actor);
    }

    private void notify(PayoutInstruction instruction, java.util.function.Consumer<PayoutOutcomeHandler> call) {
        handlers.orderedStream().filter(h -> h.sourceType().equals(instruction.getSourceType())).forEach(call);
    }

    private void step(PayoutInstruction instruction, String from, String to, String note, String actor) {
        em.persist(new PayoutRecords.Step(instruction.getRef(), from, to, note, actor));
    }

    private void lock(PayoutInstruction instruction) {
        em.flush();
        em.lock(instruction, LockModeType.PESSIMISTIC_WRITE);
        em.refresh(instruction);
    }

    private PayoutInstruction instruction(String ref) {
        return em.createQuery("select i from PayoutInstruction i where i.ref = :r", PayoutInstruction.class)
                .setParameter("r", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Payout instruction " + ref + " not found"));
    }

    private static void requireBank(PayoutRecords.BankPayment bank, String status, String action) {
        if (!status.equals(bank.getStatus())) {
            throw new IllegalArgumentException("The simulated bank cannot " + action + " payment "
                    + bank.getProviderReference() + ": it is " + bank.getStatus().toLowerCase(Locale.ROOT) + ".");
        }
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    static String label(String status) {
        return status == null ? "" : status.toLowerCase(Locale.ROOT);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new com.nsure.common.exception.InvalidFieldException(field, field + " is required.");
        }
        return value.trim();
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    static String mask(String account) {
        String value = account == null ? "" : account.trim();
        return value.length() <= 4 ? value : "•••" + value.substring(value.length() - 4);
    }
}
