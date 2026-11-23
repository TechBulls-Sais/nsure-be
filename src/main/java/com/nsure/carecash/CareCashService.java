package com.nsure.carecash;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.Money;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.InvalidFieldException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.payment.PayoutInstruction;
import com.nsure.payment.PayoutService;
import com.nsure.portfolio.ClaimWorkflow;
import com.nsure.portfolio.InsurancePolicy;
import com.nsure.portfolio.PolicyLifeCover;
import com.nsure.portfolio.PolicyWorkflow;
import com.nsure.portfolio.ReferenceNumbers;
import com.nsure.portfolio.WorkflowHistory;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Care Cash benefit engine (Nsure Life specification section 07). A hospital event is assessed benefit by
 * benefit: ward days, ICU days at a higher rate (capped per event), one surgical benefit, long-stay uplifts, day
 * cases, maternity per live birth (limited per life, with a waiting period), accidental death for higher daily tiers
 * (once per life), and a premium waiver after a long admission. Day limits apply per event (shared with a linked
 * readmission), per life per policy year (a stay over the cover anniversary is split between the years), per life for
 * life (including migrated opening days) and across the policy.
 *
 * <p>Every number and interpretation comes from the approved {@link CareCashRules} version in force on the admission
 * date (demo defaults until decision D04 is signed), and the trace names it with every cap, count and exclusion.
 * Cover is judged on the policy's history as at the admission date, for an insured life of the policy, from that
 * life's own waiting start, and ends at the termination age. Days are reserved by an assessed event, consumed by its
 * approval, and released by a decline or a reversal.
 */
@Service
public class CareCashService {

    public static final String PRODUCT = "Care Cash";
    private static final Set<String> TYPES = Set.of("ADMISSION", "DAY_CASE", "MATERNITY", "ACCIDENTAL_DEATH");

    public record EventView(String ref, String policyRef, String customerName, String lifeName, String eventType,
            String cause, LocalDate admitDate, LocalDate dischargeDate, int icuDays, boolean surgery,
            BigDecimal dailyBenefit, int stayDays, int paidDays, BigDecimal amount, int waiverMonths, List<String> trace,
            String status, String createdBy, LocalDateTime createdAt, String decidedBy, String decisionNote,
            String paymentReference, Payment payment, String lifeKey, Integer ruleVersion, boolean preExisting,
            int births, String linkedEventRef, int daysNextYear, String procedure, String deathEvidence,
            LocalDate waiverFrom, LocalDate waiverTo, String reversedBy, LocalDateTime reversedAt,
            String reversalReason) {
    }

    /**
     * The payment instruction (NS-SH05-007): who it pays and how, when it was sent, when the provider confirmed it
     * settled, and why it failed. Null fields are steps not reached.
     */
    public record Payment(String payeeName, String payoutMethod, String accountRef, String sentBy,
            LocalDateTime sentAt, LocalDateTime settledAt, String failureReason) {
    }

    /** The Care Cash event states. A payment is in flight between SENT and PAID; FAILED waits to be sent again. */
    static final String ASSESSED = "ASSESSED";
    static final String APPROVED = "APPROVED";
    static final String DECLINED = "DECLINED";
    static final String REVERSED = "REVERSED";
    static final String PAYMENT_SENT = "PAYMENT_SENT";
    static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    static final String PAID = "PAID";

    /**
     * A life's day counters: consumed by approved events this policy year and for life (opening days included),
     * days reserved by events still being assessed, and maternity claims.
     */
    public record Counters(String lifeName, int usedThisYear, int usedLifetime, int maternityClaims, String lifeKey,
            int reservedDays, int openingDays) {
    }

    public record PolicyView(String policyRef, String customerName, String status, BigDecimal dailyBenefit,
            LocalDate coverStartDate, int policyDaysUsed, int policyDaysLimit, List<Counters> lives,
            List<EventView> events, Map<String, Object> rules) {
    }

    /** An insured life of a Care Cash policy, keyed as claims key it (the main insured is PRINCIPAL or LIFE-id). */
    record Life(String key, String name, LocalDate dateOfBirth, LocalDate waitingStart, String status) {
    }

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final Outbox outbox;
    private final ReferenceNumbers numbers;
    private final EvidenceChain audit;
    private final WorkflowHistory history;
    private final PolicyWorkflow workflow;
    private final PayoutService payouts;
    private final CareCashRules ruleSets;
    private final ClaimWorkflow claims;

    /** Payout instructions for Care Cash events are of this source type. */
    public static final String PAYOUT_SOURCE = "CARE_CASH";

    public CareCashService(EntityManager em, PlatformSecurityContext security, Outbox outbox, ReferenceNumbers numbers,
            EvidenceChain audit, WorkflowHistory history, PolicyWorkflow workflow, PayoutService payouts,
            CareCashRules ruleSets, ClaimWorkflow claims) {
        this.payouts = payouts;
        this.em = em;
        this.security = security;
        this.outbox = outbox;
        this.numbers = numbers;
        this.audit = audit;
        this.history = history;
        this.workflow = workflow;
        this.ruleSets = ruleSets;
        this.claims = claims;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> policies() {
        user("READ_CLAIM");
        return em.createQuery("select p from InsurancePolicy p where p.product = :p order by p.id desc",
                        InsurancePolicy.class)
                .setParameter("p", PRODUCT).getResultList().stream().map(p -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("policyRef", p.getRef());
                    row.put("customerName", p.getCustomerName());
                    row.put("dailyBenefit", p.getAmount());
                    row.put("status", p.getStatus());
                    row.put("coverStartDate", p.getCoverStartDate());
                    return row;
                }).toList();
    }

    @Transactional(readOnly = true)
    public PolicyView policy(String policyRef) {
        user("READ_CLAIM");
        InsurancePolicy p = careCash(policyRef);
        List<CareCashEvent> events = events(policyRef);
        LocalDate today = LocalDate.now();
        CareCashRules.Values rules = ruleSets.inForce(today);
        List<Life> insured = lives(p);
        List<Counters> lives = new ArrayList<>();
        for (Life life : insured) {
            int opening = opening(p.getRef(), life.key(), false);
            lives.add(new Counters(life.name(), consumedInYear(events, p, life.key(), today, rules)
                    + opening(p.getRef(), life.key(), true), consumed(events, p, life.key()) + opening,
                    (int) events.stream().filter(e -> live(e) && life.key().equals(key(p, e))
                            && "MATERNITY".equals(e.eventType) && e.amount.signum() > 0).count(),
                    life.key(), reserved(events, p, life.key()), opening));
        }
        Map<String, Object> shown = new LinkedHashMap<>();
        rules.rules().fields().forEachRemaining(f -> shown.put(f.getKey(), f.getValue().isNumber()
                ? f.getValue().numberValue() : f.getValue().asText()));
        shown.put("ruleVersion", rules.version());
        shown.put("ruleLabel", rules.label());
        // Older readers of this view look for these names.
        shown.put("accidentalDeath", rules.i("accidentalDeathAmount"));
        shown.put("maternityMaxClaims", rules.i("maternityMaxClaims"));
        return new PolicyView(p.getRef(), p.getCustomerName(), p.getStatus(), p.getAmount(), p.getCoverStartDate(),
                consumed(events, p, null) + reserved(events, p, null) + openingPolicy(p.getRef()), rules.i("policyDayLimit"),
                lives, events.stream().map(this::view).toList(), shown);
    }

    /** Works the event out without recording it. */
    @Transactional(readOnly = true)
    public EventView assess(String policyRef, JsonNode body) {
        AppUser user = user("CREATE_CLAIM");
        return view(calculate(careCash(policyRef), body, user.getUsername()));
    }

    /**
     * {lifeName?, eventType, cause, admitDate, dischargeDate?, icuDays?, surgery?, procedure?, preExisting?, births?,
     * deathEvidence?}: records the assessed event; its days are reserved until it is approved or declined.
     */
    @Transactional
    public EventView record(String policyRef, JsonNode body) {
        AppUser user = user("CREATE_CLAIM");
        InsurancePolicy p = careCash(policyRef);
        // Shared-limit priority (demo default FIRST_RECORDED): the policy row is locked, so concurrent family
        // admissions near a cap are assessed one after another in the order they are recorded.
        em.lock(p, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        String type = upper(text(body, "eventType"));
        if (("DAY_CASE".equals(type) || body.path("surgery").asBoolean(false)) && text(body, "procedure") == null) {
            throw new InvalidFieldException("procedure", "Name the procedure performed (evidence for a day case or"
                    + " the surgical benefit).");
        }
        if ("ACCIDENTAL_DEATH".equals(type) && text(body, "deathEvidence") == null) {
            throw new InvalidFieldException("deathEvidence", "Record the death evidence seen (e.g. death certificate"
                    + " and police report).");
        }
        CareCashEvent e = calculate(p, body, user.getUsername());
        e.ref = numbers.next("CCE-", 6, () -> 0);
        em.persist(e);
        return view(e);
    }

    @Transactional(readOnly = true)
    public List<EventView> allEvents() {
        user("READ_CLAIM");
        return em.createQuery("select e from CareCashEvent e order by e.id desc", CareCashEvent.class).getResultList()
                .stream().map(this::view).toList();
    }

    /**
     * {approve, note}: an approver other than the person who recorded the event. Approval consumes the reserved days
     * (BenefitLimitConsumed), applies a premium waiver once per waiver window (PremiumWaived), and an approved
     * accidental death ends the life's cover (and the policy's, for the main insured).
     */
    @Transactional
    public EventView decide(String ref, JsonNode body) {
        AppUser user = user("APPROVE_CLAIM");
        CareCashEvent e = event(ref);
        if (!ASSESSED.equals(e.status)) {
            throw new ConflictException(ref + " is already " + e.status.toLowerCase(Locale.ROOT).replace('_', ' ') + ".");
        }
        if (user.getUsername().equalsIgnoreCase(e.createdBy)) {
            throw new NoAuthorizationException("You recorded " + ref + ", so someone else must approve it.");
        }
        String note = text(body, "note");
        if (note == null || note.length() < 5) {
            throw new IllegalArgumentException("Give a note for the decision.");
        }
        boolean approve = body.path("approve").asBoolean(false);
        e.status = approve ? APPROVED : DECLINED;
        e.decidedBy = user.getUsername();
        e.decidedAt = LocalDateTime.now();
        e.decisionNote = note;
        InsurancePolicy p = workflow.policy(e.policyRef);
        if (approve && e.waiverMonths > 0) {
            waive(e, p, user.getUsername());
        }
        if (approve && e.paidDays > 0) {
            // SH05: the days are consumed by the approved event, not by recording an assessment that may be declined.
            outbox.publish(Events.BENEFIT_LIMIT_CONSUMED, e.ref, Map.of("policyRef", e.policyRef, "lifeName",
                    e.lifeName, "days", e.paidDays, "eventRef", e.ref, "amount", e.amount.toPlainString()),
                    user.getUsername());
        } else if (approve) {
            outbox.publish(Events.BENEFIT_LIMIT_CONSUMED, e.ref, Map.of("policyRef", e.policyRef, "lifeName",
                    e.lifeName, "days", 0, "eventRef", e.ref, "amount", e.amount.toPlainString()), user.getUsername());
        }
        if (approve && "ACCIDENTAL_DEATH".equals(e.eventType) && e.amount.signum() > 0) {
            lifeEnded(e, p, user.getUsername());
        }
        audit.record(user.getUsername(), "Care Cash event " + e.status.toLowerCase(Locale.ROOT), "Policy", e.policyRef,
                ref + " " + Money.display(e.amount) + ": " + note);
        return view(e);
    }

    /**
     * {reason}: reverses an approved event that has not been paid (section 07 reversals): its days are released by
     * a corrective BenefitLimitConsumed, and a premium waiver it gave is undone (PremiumWaiverReversed).
     */
    @Transactional
    public EventView reverse(String ref, JsonNode body) {
        AppUser user = user("APPROVE_CLAIM");
        CareCashEvent e = event(ref);
        if (!APPROVED.equals(e.status) && !PAYMENT_FAILED.equals(e.status)) {
            throw new ConflictException(ref + " is " + e.status.toLowerCase(Locale.ROOT).replace('_', ' ')
                    + "; only an approved event that is not paid or in payment can be reversed.");
        }
        String reason = text(body, "reason");
        if (reason == null || reason.length() < 5) {
            throw new IllegalArgumentException("Give the reason for reversing the event.");
        }
        e.status = REVERSED;
        e.reversedBy = user.getUsername();
        e.reversedAt = LocalDateTime.now();
        e.reversalReason = reason.length() <= 500 ? reason : reason.substring(0, 500);
        InsurancePolicy p = workflow.policy(e.policyRef);
        if (e.waiverFrom != null && e.waiverMonths > 0 && p.getNextPremiumDue() != null) {
            p.setNextPremiumDue(p.getNextPremiumDue().minusMonths(e.waiverMonths));
            if (p.getGracePeriodEnd() != null) {
                p.setGracePeriodEnd(p.getGracePeriodEnd().minusMonths(e.waiverMonths));
            }
            history.record(WorkflowHistory.POLICY, p.getRef(), p.getCustomerName(), "Premium Waiver Reversed",
                    p.getStatus(), p.getStatus(), "Waiver of " + e.waiverMonths + " months from " + e.waiverFrom
                            + " undone: " + ref + " reversed", user.getUsername());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("policyRef", p.getRef());
            data.put("eventRef", e.ref);
            data.put("months", e.waiverMonths);
            data.put("waivedFrom", e.waiverFrom.toString());
            data.put("waivedTo", e.waiverTo == null ? "" : e.waiverTo.toString());
            data.put("reason", e.reversalReason);
            outbox.publish(Events.PREMIUM_WAIVER_REVERSED, p.getRef(), data, user.getUsername());
        }
        outbox.publish(Events.BENEFIT_LIMIT_CONSUMED, e.ref, Map.of("policyRef", e.policyRef, "lifeName", e.lifeName,
                "days", -e.paidDays, "eventRef", e.ref, "amount", e.amount.negate().toPlainString(), "reversal", true),
                user.getUsername());
        audit.record(user.getUsername(), "Care Cash event reversed", "Policy", e.policyRef, ref + ": " + reason);
        return view(e);
    }

    /**
     * {lifeName?, lifetimeDays, yearDays, asOf?, note}: days a life already used before this system (migration,
     * D17), counted against its lifetime and current-year limits.
     */
    @Transactional
    public PolicyView openingDays(String policyRef, JsonNode body) {
        AppUser user = user("UPDATE_CLAIM");
        InsurancePolicy p = careCash(policyRef);
        Life life = life(p, text(body, "lifeName"));
        int lifetime = body.path("lifetimeDays").asInt(-1);
        int year = body.path("yearDays").asInt(-1);
        String note = text(body, "note");
        if (lifetime < 0 || year < 0 || year > lifetime || lifetime > 5000) {
            throw new IllegalArgumentException("Give the lifetime and this year's opening days (0 or more, the year's"
                    + " no more than the lifetime's).");
        }
        if (note == null || note.length() < 5) {
            throw new IllegalArgumentException("Say where the opening days come from (e.g. the previous system).");
        }
        CareCashOpeningDays row = new CareCashOpeningDays();
        row.policyRef = p.getRef();
        row.lifeKey = life.key();
        row.lifeName = life.name();
        row.lifetimeDays = lifetime;
        row.yearDays = year;
        row.asOf = text(body, "asOf") == null ? LocalDate.now() : date(body, "asOf");
        row.note = note.length() <= 300 ? note : note.substring(0, 300);
        row.recordedBy = user.getUsername();
        row.recordedAt = LocalDateTime.now();
        em.persist(row);
        audit.record(user.getUsername(), "Care Cash opening days", "Policy", p.getRef(), life.name() + ": "
                + lifetime + " lifetime, " + year + " this year · " + row.note);
        em.flush();
        return policy(policyRef);
    }

    /**
     * {payeeName?, payoutMethod, accountRef}: sends the payment instruction for an approved event, or sends a failed one
     * again with corrected details. It gets a unique reference and stays in flight (PAYMENT_SENT): nothing is paid or
     * posted until the provider confirms it settled (as for claim payouts, NS-SH05-007 and AT-SH05-03).
     */
    @Transactional
    public EventView pay(String ref, JsonNode body) {
        AppUser user = user("SETTLE_CLAIM");
        CareCashEvent e = event(ref);
        if (PAID.equals(e.status)) {
            throw new ConflictException(ref + " is already paid (" + e.paymentReference + "); it cannot be paid again.");
        }
        if (PAYMENT_SENT.equals(e.status)) {
            throw new ConflictException("A payment for " + ref + " is already in flight (" + e.paymentReference
                    + "). Confirm its settlement or record that it failed before sending another.");
        }
        if (!APPROVED.equals(e.status) && !PAYMENT_FAILED.equals(e.status)) {
            throw new ConflictException(ref + " is " + e.status.toLowerCase(Locale.ROOT).replace('_', ' ')
                    + "; only an approved event is paid.");
        }
        if (e.amount == null || e.amount.signum() <= 0) {
            throw new IllegalArgumentException(ref + " has no benefit to pay.");
        }
        String payee = text(body, "payeeName") == null ? e.customerName : text(body, "payeeName");
        String method = text(body, "payoutMethod");
        String account = text(body, "accountRef");
        if (payee == null || method == null || account == null) {
            throw new InvalidFieldException(method == null ? "payoutMethod" : account == null ? "accountRef" : "payeeName",
                    "Give the payee, the payout method and the account or wallet number.");
        }
        if (payee.length() > 150 || method.length() > 40 || account.length() > 60) {
            throw new IllegalArgumentException("The payee details are too long.");
        }
        if (PAYMENT_FAILED.equals(e.status) && method.equals(e.payoutMethod) && account.equals(e.payoutAccount)) {
            throw new IllegalArgumentException("The last payment to " + e.payoutMethod + " " + mask(e.payoutAccount)
                    + " failed. Correct the payout details before sending it again.");
        }
        String previous = e.paymentReference;
        e.payeeName = payee;
        e.payoutMethod = method;
        e.payoutAccount = account;
        // NS-SH05-007 / NS-TXN-003: a persisted payout instruction (unique reference and idempotency key), approved by
        // the payer and handed to the payout rail in this transaction; a failed one is replaced, never reused.
        PayoutInstruction instruction = payouts.authorise(new PayoutService.Request(PAYOUT_SOURCE, e.ref, e.policyRef,
                payee, method, account, e.amount, "BWP"), user.getUsername());
        payouts.submit(instruction, user.getUsername());
        e.paymentReference = instruction.getRef();
        e.paymentSentBy = user.getUsername();
        e.paymentSentAt = LocalDateTime.now();
        e.paymentFailure = null;
        e.paidAt = null;
        e.status = PAYMENT_SENT;
        audit.record(user.getUsername(), "Care Cash payment sent", "Policy", e.policyRef, ref + " "
                + Money.display(e.amount) + " to " + payee + " by " + method + " " + mask(account) + " · "
                + e.paymentReference + (previous == null ? "" : " (replaces failed " + previous + ")")
                + " · awaiting the provider's confirmation");
        return view(e);
    }

    /**
     * {providerReference?}: the provider confirms the payment settled; only now is the event PAID and the benefit
     * posted. A confirmation naming another instruction is refused, so an old one cannot settle a resent payment.
     */
    @Transactional
    public EventView confirmPayment(String ref, JsonNode body) {
        AppUser user = user("SETTLE_CLAIM");
        CareCashEvent e = event(ref);
        if (PAID.equals(e.status)) {
            throw new ConflictException(ref + " is already paid (" + e.paymentReference + ").");
        }
        if (!PAYMENT_SENT.equals(e.status)) {
            throw new ConflictException(ref + " has no payment in flight to confirm.");
        }
        String confirmed = text(body, "providerReference");
        boolean same = confirmed == null || confirmed.equals(e.paymentReference) || payouts.byReference(confirmed)
                .map(i -> i.getRef().equals(e.paymentReference)).orElse(false);
        if (!same) {
            throw new IllegalArgumentException("This confirmation is for " + confirmed + ", but the payment in flight"
                    + " on " + ref + " is " + e.paymentReference + ".");
        }
        payouts.byReference(e.paymentReference).ifPresent(i -> payouts.settleManually(i, user.getUsername()));
        markPaid(e, user.getUsername());
        return view(e);
    }

    /** Settled (confirmed by a person or by the rail): PAID and the benefit posted. */
    private void markPaid(CareCashEvent e, String actor) {
        String ref = e.ref;
        e.status = PAID;
        e.paidAt = LocalDateTime.now();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyRef", e.policyRef);
        data.put("customerName", e.customerName == null ? "" : e.customerName);
        data.put("product", PRODUCT);
        data.put("benefitRef", e.ref);
        data.put("benefitType", "CARE_CASH_" + e.eventType);
        data.put("amount", e.amount.toPlainString());
        data.put("paymentReference", e.paymentReference);
        outbox.publish(Events.BENEFIT_PAID, e.ref, data, actor);
        audit.record(actor, "Care Cash payment settled", "Policy", e.policyRef, ref + " "
                + Money.display(e.amount) + " · " + e.paymentReference + " · settlement confirmed");
    }

    // ── the payout rail's answers (PayoutService → CareCashPayoutHandler) ─────────────────────────

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void settledByRail(PayoutInstruction instruction, String actor) {
        CareCashEvent e = event(instruction.getSourceRef());
        if (PAYMENT_SENT.equals(e.status) && instruction.getRef().equals(e.paymentReference)) {
            markPaid(e, actor);
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void failedByRail(PayoutInstruction instruction, String reason, String actor) {
        CareCashEvent e = event(instruction.getSourceRef());
        if (PAYMENT_SENT.equals(e.status) && instruction.getRef().equals(e.paymentReference)) {
            e.status = PAYMENT_FAILED;
            e.paymentFailure = reason.length() <= 500 ? reason : reason.substring(0, 500);
            audit.record(actor, "Care Cash payment failed", "Policy", e.policyRef, e.ref + " · " + e.paymentReference
                    + " · " + reason);
        }
    }

    /** A settled payment came back: unpaid again, sent again with corrected details. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void returnedByRail(PayoutInstruction instruction, String reason, String actor) {
        CareCashEvent e = event(instruction.getSourceRef());
        if (PAID.equals(e.status) && instruction.getRef().equals(e.paymentReference)) {
            e.status = PAYMENT_FAILED;
            e.paidAt = null;
            e.paymentFailure = ("Returned after settlement: " + reason).length() <= 500
                    ? "Returned after settlement: " + reason : ("Returned after settlement: " + reason).substring(0, 500);
            audit.record(actor, "Care Cash payment returned", "Policy", e.policyRef, e.ref + " · " + e.paymentReference
                    + " · " + reason);
        }
    }

    /** {reason}: the provider or bank failed or returned the payment; it is sent again with corrected details. */
    @Transactional
    public EventView paymentFailed(String ref, JsonNode body) {
        AppUser user = user("SETTLE_CLAIM");
        CareCashEvent e = event(ref);
        if (!PAYMENT_SENT.equals(e.status)) {
            throw new ConflictException(ref + " has no payment in flight" + (PAID.equals(e.status)
                    ? "; it is already paid." : "."));
        }
        String reason = text(body, "reason");
        if (reason == null) {
            throw new IllegalArgumentException("Give the reason the provider gave for the failed or returned payment.");
        }
        if (reason.length() > 500) {
            throw new IllegalArgumentException("The reason is too long.");
        }
        payouts.byReference(e.paymentReference).ifPresent(i -> payouts.failManually(i, reason, user.getUsername()));
        e.status = PAYMENT_FAILED;
        e.paymentFailure = reason;
        audit.record(user.getUsername(), "Care Cash payment failed", "Policy", e.policyRef, ref + " · "
                + e.paymentReference + " · " + reason);
        return view(e);
    }

    private static String mask(String account) {
        return account == null || account.length() <= 4 ? account : "•••" + account.substring(account.length() - 4);
    }

    // ── waiver and life end ───────────────────────────────────────────────────────────────────────

    /**
     * Section 07 premium waiver (demo defaults, D04): it starts at the next unpaid premium (premiums already collected
     * are not refunded) and covers the whole premium; a second event while a waiver window is still running does not
     * add another window.
     */
    private void waive(CareCashEvent e, InsurancePolicy p, String actor) {
        if (p.getNextPremiumDue() == null) {
            return;
        }
        CareCashEvent running = events(e.policyRef).stream().filter(x -> x != e && x.waiverTo != null
                        && Set.of(APPROVED, PAYMENT_SENT, PAYMENT_FAILED, PAID).contains(x.status)
                        && !x.waiverTo.isBefore(LocalDate.now()))
                .findFirst().orElse(null);
        if (running != null) {
            e.trace = e.trace + "\nPremium waiver: not applied again, the premium is already waived until "
                    + running.waiverTo + " (" + running.ref + ").";
            e.waiverMonths = 0;
            return;
        }
        e.waiverFrom = p.getNextPremiumDue();
        e.waiverTo = p.getNextPremiumDue().plusMonths(e.waiverMonths).minusDays(1);
        p.setNextPremiumDue(p.getNextPremiumDue().plusMonths(e.waiverMonths));
        if (p.getGracePeriodEnd() != null) {
            p.setGracePeriodEnd(p.getGracePeriodEnd().plusMonths(e.waiverMonths));
        }
        history.record(WorkflowHistory.POLICY, p.getRef(), p.getCustomerName(), "Premium Waived", p.getStatus(),
                p.getStatus(), e.waiverMonths + " months of premium waived (" + e.waiverFrom + " to " + e.waiverTo
                        + ") after " + e.stayDays + " admission days (" + e.ref + ")", actor);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyRef", p.getRef());
        data.put("eventRef", e.ref);
        data.put("months", e.waiverMonths);
        data.put("waivedFrom", e.waiverFrom.toString());
        data.put("waivedTo", e.waiverTo.toString());
        outbox.publish(Events.PREMIUM_WAIVED, p.getRef(), data, actor);
    }

    /** An approved accidental death ends that life's cover; for the main insured it ends the policy (cause DEATH). */
    private void lifeEnded(CareCashEvent e, InsurancePolicy p, String actor) {
        String principal = principalKey(p);
        if (principal.equals(key(p, e)) && Set.of(PolicyWorkflow.ACTIVE, PolicyWorkflow.GRACE,
                PolicyWorkflow.ACTIVE_NO_COVER).contains(p.getStatus())) {
            workflow.terminate(p.getRef(), "DEATH", "Accidental death of " + e.lifeName + " (" + e.ref + ")",
                    e.admitDate, actor);
        } else {
            history.record(WorkflowHistory.POLICY, p.getRef(), p.getCustomerName(), "Life Ceased", p.getStatus(),
                    p.getStatus(), e.lifeName + " died on " + e.admitDate + " (accidental death " + e.ref
                            + "); no further benefits for this life", actor);
        }
    }

    // ── the calculation ───────────────────────────────────────────────────────────────────────────

    private CareCashEvent calculate(InsurancePolicy p, JsonNode body, String actor) {
        String type = upper(text(body, "eventType"));
        String cause = upper(text(body, "cause"));
        if (type == null || !TYPES.contains(type)) {
            throw new InvalidFieldException("eventType", "eventType must be ADMISSION, DAY_CASE, MATERNITY or ACCIDENTAL_DEATH.");
        }
        cause = cause == null ? "ILLNESS" : cause;
        if (!Set.of("ILLNESS", "ACCIDENT").contains(cause)) {
            throw new InvalidFieldException("cause", "cause must be ILLNESS or ACCIDENT.");
        }
        if (text(body, "admitDate") == null) {
            throw new InvalidFieldException("admitDate", "admitDate is required.");
        }
        LocalDate admit = date(body, "admitDate");
        LocalDate discharge = text(body, "dischargeDate") == null ? admit : date(body, "dischargeDate");
        if (discharge.isBefore(admit) || admit.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Check the dates: discharge cannot be before admission, and the"
                    + " admission cannot be in the future.");
        }
        Life life = life(p, text(body, "lifeName"));
        int icuDays = Math.max(0, body.path("icuDays").asInt(0));
        int nights = (int) ChronoUnit.DAYS.between(admit, discharge);
        if ("ADMISSION".equals(type) && icuDays > nights) {
            throw new InvalidFieldException("icuDays", "ICU days cannot be more than the " + nights + " nights in hospital.");
        }
        int births = Math.max(1, body.path("births").asInt(1));
        if (births > 6) {
            throw new InvalidFieldException("births", "births must be between 1 and 6.");
        }
        CareCashRules.Values r = ruleSets.inForce(admit);
        BigDecimal daily = p.getAmount();
        List<String> trace = new ArrayList<>();
        CareCashEvent e = new CareCashEvent();
        e.policyRef = p.getRef();
        e.customerName = p.getCustomerName();
        e.lifeName = life.name();
        e.lifeKey = life.key();
        e.eventType = type;
        e.cause = cause;
        e.admitDate = admit;
        e.dischargeDate = discharge;
        e.icuDays = icuDays;
        e.surgery = body.path("surgery").asBoolean(false);
        e.procedureName = clip(text(body, "procedure"), 150);
        e.deathEvidence = clip(text(body, "deathEvidence"), 300);
        e.preExisting = body.path("preExisting").asBoolean(false) ? 1 : 0;
        e.births = births;
        e.ruleVersion = r.version();
        e.dailyBenefit = daily;
        e.amount = Money.of(BigDecimal.ZERO);
        e.status = ASSESSED;
        e.createdBy = actor;
        e.createdAt = LocalDateTime.now();
        trace.add("Daily benefit " + Money.display(daily) + " on " + p.getRef() + " for " + life.name() + ".");
        trace.add("Rules: Care Cash rule set v" + r.version() + (r.label() == null ? "" : " — " + r.label()) + ".");

        // Cover on the admission date, from the policy's effective-dated history (not its status today).
        if (!claims.coverInForceOnDate(p, admit)) {
            trace.add("Not payable: the policy was not in cover on " + admit + " (" + claims.policyStateOn(p, admit) + ").");
            return finish(e, trace);
        }
        List<CareCashEvent> earlier = events(p.getRef());
        CareCashEvent death = earlier.stream().filter(x -> live(x) && "ACCIDENTAL_DEATH".equals(x.eventType)
                        && x.amount.signum() > 0 && life.key().equals(key(p, x)) && consuming(x)
                        && x.admitDate.isBefore(admit))
                .findFirst().orElse(null);
        if (death != null) {
            trace.add("Not payable: " + life.name() + " died on " + death.admitDate + " (" + death.ref + ").");
            return finish(e, trace);
        }
        if ("CEASED".equalsIgnoreCase(life.status())) {
            trace.add("Not payable: " + life.name() + "'s cover has ceased.");
            return finish(e, trace);
        }
        if (life.dateOfBirth() != null) {
            int age = Period.between(life.dateOfBirth(), admit).getYears();
            if (age >= r.i("terminationAge")) {
                trace.add("Not payable: cover for " + life.name() + " ended at age " + r.i("terminationAge") + " (age "
                        + age + " on " + admit + ").");
                return finish(e, trace);
            }
            LocalDate start = life.waitingStart() == null ? p.getCoverStartDate() : life.waitingStart();
            int entry = start == null ? age : Period.between(life.dateOfBirth(), start).getYears();
            if (entry < r.i("entryAgeMin") || entry > r.i("entryAgeMax")) {
                trace.add("Note: entry age " + entry + " is outside " + r.i("entryAgeMin") + "–" + r.i("entryAgeMax")
                        + "; refer to underwriting.");
            }
        }
        // Waiting periods run from this life's own waiting start (a life added later waits from when it was added).
        LocalDate waitStart = life.waitingStart() != null ? life.waitingStart() : p.getCoverStartDate();
        int wait = "MATERNITY".equals(type) ? r.i("maternityWaitMonths")
                : "ACCIDENT".equals(cause) ? r.i("accidentWaitMonths") : r.i("illnessWaitMonths");
        if (waitStart != null && admit.isBefore(waitStart.plusMonths(wait))) {
            trace.add("Not payable: within the " + wait + "-month waiting period for "
                    + ("MATERNITY".equals(type) ? "maternity" : "ACCIDENT".equals(cause) ? "accidents" : "illness")
                    + " (waiting from " + waitStart + ").");
            return finish(e, trace);
        }
        if (wait == 0) {
            trace.add("Accident: no waiting period.");
        }
        if (e.preExisting == 1 && !"ACCIDENT".equals(cause) && waitStart != null
                && admit.isBefore(waitStart.plusMonths(r.i("preExistingWaitMonths")))) {
            trace.add("Not payable: a pre-existing condition is excluded for " + r.i("preExistingWaitMonths")
                    + " months (waiting from " + waitStart + ").");
            return finish(e, trace);
        }

        if ("ACCIDENTAL_DEATH".equals(type)) {
            BigDecimal minimum = BigDecimal.valueOf(r.i("accidentalDeathMinDaily"));
            boolean already = earlier.stream().anyMatch(x -> live(x) && "ACCIDENTAL_DEATH".equals(x.eventType)
                    && x.amount.signum() > 0 && life.key().equals(key(p, x)));
            if (!"ACCIDENT".equals(cause)) {
                trace.add("Not payable: the accidental death benefit needs an accidental cause.");
            } else if (daily.compareTo(minimum) < 0) {
                trace.add("Not payable: accidental death applies to daily tiers of " + Money.display(minimum)
                        + " and above.");
            } else if (already) {
                trace.add("Not payable: the accidental death benefit for " + life.name() + " has already been claimed"
                        + " (once per life).");
            } else {
                e.amount = Money.of(BigDecimal.valueOf(r.i("accidentalDeathAmount")));
                trace.add("Accidental death benefit " + Money.display(e.amount) + " ("
                        + r.s("accidentalDeathLimit").toLowerCase(Locale.ROOT) + " of the day limits; once per life).");
            }
            return finish(e, trace);
        }
        // Same-day overlaps: maternity and an admission or day case for the same life and day are one stay.
        CareCashEvent overlap = earlier.stream().filter(x -> live(x) && life.key().equals(key(p, x))
                        && overlaps(x, admit, discharge)
                        && ("MATERNITY".equals(type) != "MATERNITY".equals(x.eventType)
                                || ("DAY_CASE".equals(type) && "ADMISSION".equals(x.eventType))))
                .findFirst().orElse(null);
        if (overlap != null) {
            trace.add("Not payable: " + life.name() + "'s " + label(overlap.eventType) + " " + overlap.ref
                    + " already covers " + admit + " (no double counting, demo rule).");
            return finish(e, trace);
        }
        if ("MATERNITY".equals(type)) {
            long had = earlier.stream().filter(x -> live(x) && "MATERNITY".equals(x.eventType)
                    && life.key().equals(key(p, x)) && x.amount.signum() > 0).count();
            if (had >= r.i("maternityMaxClaims")) {
                trace.add("Not payable: " + life.name() + " has already had " + r.i("maternityMaxClaims")
                        + " maternity claims.");
            } else {
                int paidBirths = "PER_BIRTH".equals(r.s("multipleBirths")) ? births : 1;
                e.amount = Money.of(daily.multiply(BigDecimal.valueOf((long) r.i("maternityMultiple") * paidBirths)));
                trace.add("Maternity: " + r.i("maternityMultiple") + " × daily benefit" + (paidBirths > 1 ? " × "
                        + paidBirths + " live births" : "") + " = " + Money.display(e.amount) + " (claim " + (had + 1)
                        + " of " + r.i("maternityMaxClaims") + ").");
            }
            return finish(e, trace);
        }

        // Day limits: what this life and the policy have consumed or reserved, plus opening days.
        LocalDate yearStart = yearStart(p, admit, r);
        LocalDate nextYear = yearStart.plusYears(1);
        // Opening (migrated) days of the current year count in the policy year that contains today.
        int remainingYear = r.i("yearDayLimit") - consumedInYear(earlier, p, life.key(), admit, r)
                - reservedInYear(earlier, p, life.key(), admit, r) - (inCurrentYear(p, admit, r)
                ? opening(p.getRef(), life.key(), true) : 0);
        int remainingNextYear = r.i("yearDayLimit") - consumedInYear(earlier, p, life.key(), nextYear, r)
                - reservedInYear(earlier, p, life.key(), nextYear, r) - (inCurrentYear(p, nextYear, r)
                ? opening(p.getRef(), life.key(), true) : 0);
        int remainingLife = r.i("lifetimeDayLimit") - consumed(earlier, p, life.key()) - reserved(earlier, p, life.key())
                - opening(p.getRef(), life.key(), false);
        int remainingPolicy = r.i("policyDayLimit") - consumed(earlier, p, null) - reserved(earlier, p, null)
                - openingPolicy(p.getRef());
        if ("DAY_CASE".equals(type)) {
            e.stayDays = 1;
            int payable = Math.min(1, Math.max(0, Math.min(remainingYear, Math.min(remainingLife, remainingPolicy))));
            if (payable == 0) {
                trace.add("Not payable: a day limit is exhausted.");
                return finish(e, trace);
            }
            e.paidDays = 1;
            e.amount = Money.of(daily.multiply(BigDecimal.valueOf(r.i("dayCasePercent"))).divide(BigDecimal.valueOf(100)));
            trace.add("Day case (" + (e.procedureName == null ? "procedure not named" : e.procedureName) + "): "
                    + r.i("dayCasePercent") + "% of the daily benefit = " + Money.display(e.amount)
                    + "; counts 1 day against limits.");
            if (e.surgery) {
                BigDecimal surgery = Money.of(daily.multiply(BigDecimal.valueOf(r.i("surgeryPercent"))).divide(BigDecimal.valueOf(100)));
                e.amount = e.amount.add(surgery);
                trace.add("Surgical benefit: " + r.i("surgeryPercent") + "% of the daily benefit, once per event = "
                        + Money.display(surgery) + ".");
            }
            return finish(e, trace);
        }

        e.stayDays = nights;
        if (nights < r.i("qualifyingNights")) {
            trace.add("Not payable: " + nights + " night(s); an admission qualifies from " + r.i("qualifyingNights")
                    + " consecutive nights.");
            return finish(e, trace);
        }
        trace.add(nights + " nights from " + admit + " to " + discharge + ": qualifies, paid from day one"
                + " (admission day counted, discharge day not).");
        // A readmission soon after a discharge for the same life is the same event for the per-event limits.
        CareCashEvent linked = earlier.stream().filter(x -> live(x) && "ADMISSION".equals(x.eventType)
                        && life.key().equals(key(p, x)) && x.dischargeDate != null && !x.dischargeDate.isAfter(admit)
                        && ChronoUnit.DAYS.between(x.dischargeDate, admit) <= r.i("readmissionDays"))
                .findFirst().orElse(null);
        int eventRemaining = r.i("eventDayLimit");
        int icuRemaining = r.i("icuMaxDays");
        boolean surgeryPaid = false;
        if (linked != null) {
            String chain = linked.linkedEventRef == null ? linked.ref : linked.linkedEventRef;
            e.linkedEventRef = chain;
            List<CareCashEvent> sameEvent = earlier.stream().filter(x -> live(x)
                    && (chain.equals(x.ref) || chain.equals(x.linkedEventRef))).toList();
            eventRemaining -= sameEvent.stream().mapToInt(x -> x.paidDays).sum();
            icuRemaining -= sameEvent.stream().mapToInt(x -> Math.min(x.icuDays, x.paidDays)).sum();
            surgeryPaid = sameEvent.stream().anyMatch(x -> x.surgery && x.amount.signum() > 0);
            trace.add("Readmission within " + r.i("readmissionDays") + " days of " + linked.ref + ": shares event "
                    + chain + "'s limits (" + (r.i("eventDayLimit") - eventRemaining) + " days already paid).");
        }
        int payable = cap(nights, Math.max(0, eventRemaining), r.i("eventDayLimit") + " days per event", trace);
        // The policy year: a stay over the cover anniversary counts each night to the year it falls in.
        int daysThisYear = payable;
        int daysNext = 0;
        if (discharge.isAfter(nextYear) && admit.isBefore(nextYear)) {
            daysThisYear = Math.min(payable, (int) ChronoUnit.DAYS.between(admit, nextYear));
            daysNext = payable - daysThisYear;
            trace.add("The stay crosses the cover anniversary on " + nextYear + ": " + daysThisYear + " night(s) to the"
                    + " year from " + yearStart + ", " + daysNext + " to the next.");
        }
        int cappedThis = cap(daysThisYear, remainingYear, r.i("yearDayLimit") + " days per life per policy year ("
                + (r.i("yearDayLimit") - remainingYear) + " used or reserved)", trace);
        int cappedNext = daysNext == 0 ? 0 : cap(daysNext, remainingNextYear, r.i("yearDayLimit")
                + " days per life in the next policy year (" + (r.i("yearDayLimit") - remainingNextYear) + " used)", trace);
        payable = cappedThis + cappedNext;
        payable = cap(payable, remainingLife, r.i("lifetimeDayLimit") + " days per life (" + (r.i("lifetimeDayLimit")
                - remainingLife) + " used or reserved)", trace);
        payable = cap(payable, remainingPolicy, String.format(Locale.ENGLISH, "%,d", r.i("policyDayLimit"))
                + " days across the policy (" + (r.i("policyDayLimit") - remainingPolicy) + " used or reserved)", trace);
        if (payable <= 0) {
            trace.add("Not payable: a day limit is exhausted.");
            return finish(e, trace);
        }
        e.paidDays = payable;
        e.daysNextYear = Math.min(cappedNext, payable);
        int icu = Math.min(Math.min(e.icuDays, Math.max(0, icuRemaining)), payable);
        if (e.icuDays > icuRemaining) {
            trace.add("ICU days capped at " + r.i("icuMaxDays") + " per event (" + e.icuDays + " claimed"
                    + (linked == null ? "" : ", including the linked stay") + ").");
        }
        int ward = payable - icu;
        BigDecimal total = daily.multiply(BigDecimal.valueOf(ward));
        trace.add(ward + " ward day(s) × " + Money.display(daily) + " = " + Money.display(total) + ".");
        if (icu > 0) {
            BigDecimal icuAmount = daily.multiply(BigDecimal.valueOf(r.i("icuPercent"))).divide(BigDecimal.valueOf(100))
                    .multiply(BigDecimal.valueOf(icu));
            total = total.add(icuAmount);
            trace.add(icu + " ICU day(s) × " + r.i("icuPercent") + "% = " + Money.display(icuAmount)
                    + " (instead of the ward rate, not on top).");
        }
        if (e.surgery && !surgeryPaid) {
            BigDecimal surgery = daily.multiply(BigDecimal.valueOf(r.i("surgeryPercent"))).divide(BigDecimal.valueOf(100));
            total = total.add(surgery);
            trace.add("Surgical benefit (" + (e.procedureName == null ? "procedure not named" : e.procedureName) + "): "
                    + r.i("surgeryPercent") + "% of the daily benefit, once per event = " + Money.display(surgery) + ".");
        } else if (e.surgery) {
            trace.add("Surgical benefit: already paid on the linked stay (once per event).");
        }
        int stay = "PAID_DAYS".equals(r.s("longStayBasis")) ? payable : nights;
        String basis = "PAID_DAYS".equals(r.s("longStayBasis")) ? "paid days" : "nights";
        if (stay > r.i("longStay1Nights")) {
            BigDecimal uplift = daily.multiply(BigDecimal.valueOf(r.i("longStay1Multiple")));
            total = total.add(uplift);
            trace.add("Long stay beyond " + r.i("longStay1Nights") + " days (" + stay + " " + basis + "): "
                    + r.i("longStay1Multiple") + " × daily benefit = " + Money.display(uplift) + ".");
        }
        if (stay > r.i("longStay2Nights")) {
            BigDecimal uplift = daily.multiply(BigDecimal.valueOf(r.i("longStay2Multiple")));
            total = total.add(uplift);
            trace.add("Long stay beyond " + r.i("longStay2Nights") + " days: a further " + r.i("longStay2Multiple")
                    + " × daily benefit = " + Money.display(uplift) + ".");
        }
        if (nights >= r.i("waiverAfterDays") && r.i("waiverMonths") > 0) {
            e.waiverMonths = r.i("waiverMonths");
            trace.add("Premium waiver: " + r.i("waiverMonths") + " months from the next unpaid premium, after "
                    + r.i("waiverAfterDays") + " or more consecutive admission days (applied on approval).");
        }
        e.amount = Money.of(total);
        return finish(e, trace);
    }

    private static int cap(int days, int limit, String name, List<String> trace) {
        if (days > limit) {
            trace.add("Capped from " + days + " to " + Math.max(0, limit) + " day(s) by the limit of " + name + ".");
            return Math.max(0, limit);
        }
        return days;
    }

    private static CareCashEvent finish(CareCashEvent e, List<String> trace) {
        trace.add("Payable: " + Money.display(e.amount) + ".");
        e.trace = String.join("\n", trace);
        return e;
    }

    // ── counting ─────────────────────────────────────────────────────────────────────────────────

    /** Neither declined nor reversed: its days are reserved (assessed) or consumed (approved onwards). */
    private static boolean live(CareCashEvent e) {
        return !DECLINED.equals(e.status) && !REVERSED.equals(e.status);
    }

    private static boolean consuming(CareCashEvent e) {
        return Set.of(APPROVED, PAYMENT_SENT, PAYMENT_FAILED, PAID).contains(e.status);
    }

    private int consumed(List<CareCashEvent> events, InsurancePolicy p, String lifeKey) {
        return events.stream().filter(CareCashService::consuming).filter(e -> lifeKey == null
                || lifeKey.equals(key(p, e))).mapToInt(e -> e.paidDays).sum();
    }

    private int reserved(List<CareCashEvent> events, InsurancePolicy p, String lifeKey) {
        return events.stream().filter(e -> ASSESSED.equals(e.status)).filter(e -> lifeKey == null
                || lifeKey.equals(key(p, e))).mapToInt(e -> e.paidDays).sum();
    }

    private int consumedInYear(List<CareCashEvent> events, InsurancePolicy p, String lifeKey, LocalDate day,
            CareCashRules.Values r) {
        return inYear(events.stream().filter(CareCashService::consuming).toList(), p, lifeKey, day, r);
    }

    private int reservedInYear(List<CareCashEvent> events, InsurancePolicy p, String lifeKey, LocalDate day,
            CareCashRules.Values r) {
        return inYear(events.stream().filter(e -> ASSESSED.equals(e.status)).toList(), p, lifeKey, day, r);
    }

    /** Days in the policy year of {@code day}: nights before an anniversary count to the earlier year. */
    private int inYear(List<CareCashEvent> events, InsurancePolicy p, String lifeKey, LocalDate day,
            CareCashRules.Values r) {
        LocalDate from = yearStart(p, day, r);
        LocalDate to = from.plusYears(1);
        int days = 0;
        for (CareCashEvent e : events) {
            if (lifeKey != null && !lifeKey.equals(key(p, e))) {
                continue;
            }
            if (!e.admitDate.isBefore(from) && e.admitDate.isBefore(to)) {
                days += e.paidDays - e.daysNextYear;
            } else if (!e.admitDate.isBefore(from.minusYears(1)) && e.admitDate.isBefore(from)) {
                days += e.daysNextYear;
            }
        }
        return days;
    }

    private boolean inCurrentYear(InsurancePolicy p, LocalDate day, CareCashRules.Values r) {
        return yearStart(p, day, r).equals(yearStart(p, LocalDate.now(), r));
    }

    private int opening(String policyRef, String lifeKey, boolean yearOnly) {
        Long n = em.createQuery("select coalesce(sum(" + (yearOnly ? "o.yearDays" : "o.lifetimeDays") + "), 0) from"
                        + " CareCashOpeningDays o where o.policyRef = :p and o.lifeKey = :k", Long.class)
                .setParameter("p", policyRef).setParameter("k", lifeKey).getSingleResult();
        return n == null ? 0 : n.intValue();
    }

    private int openingPolicy(String policyRef) {
        Long n = em.createQuery("select coalesce(sum(o.lifetimeDays), 0) from CareCashOpeningDays o where"
                + " o.policyRef = :p", Long.class).setParameter("p", policyRef).getSingleResult();
        return n == null ? 0 : n.intValue();
    }

    /** The start of the policy year a date falls in: the cover anniversary (or 1 January, as the rules say). */
    private static LocalDate yearStart(InsurancePolicy p, LocalDate date, CareCashRules.Values r) {
        if ("CALENDAR_YEAR".equals(r.s("annualReset"))) {
            return date.withDayOfYear(1);
        }
        LocalDate start = p.getCoverStartDate() == null ? date : p.getCoverStartDate();
        long years = Math.max(0, ChronoUnit.YEARS.between(start, date));
        return start.plusYears(years);
    }

    private static boolean overlaps(CareCashEvent x, LocalDate admit, LocalDate discharge) {
        LocalDate xEnd = x.dischargeDate == null ? x.admitDate : x.dischargeDate;
        return !x.admitDate.isAfter(discharge) && !admit.isAfter(xEnd);
    }

    private static String label(String type) {
        return switch (type) {
            case "DAY_CASE" -> "day case";
            case "MATERNITY" -> "maternity event";
            case "ACCIDENTAL_DEATH" -> "accidental death";
            default -> "admission";
        };
    }

    // ── lives ────────────────────────────────────────────────────────────────────────────────────

    /** The insured lives of the policy: the main insured, then any other covered lives. */
    List<Life> lives(InsurancePolicy p) {
        List<Life> lives = new ArrayList<>();
        LocalDate dob = em.createQuery("select c.dateOfBirth from CustomerAccount c where c.ref = :r", LocalDate.class)
                .setParameter("r", p.getCustomerRef() == null ? "" : p.getCustomerRef()).getResultStream().findFirst()
                .orElse(null);
        String principal = principalKey(p);
        lives.add(new Life(principal, p.getCustomerName(), dob, p.getCoverStartDate(), "ACTIVE"));
        for (PolicyLifeCover l : em.createQuery("select l from PolicyLifeCover l where l.policy = :p order by"
                + " l.displayOrder", PolicyLifeCover.class).setParameter("p", p).getResultList()) {
            if (l.getRelationship() != null && l.getRelationship().isSelfRelationship()) {
                continue;
            }
            lives.add(new Life("LIFE-" + l.getId(), l.getFullName(), l.getDateOfBirth(),
                    l.getWaitingPeriodStart() != null ? l.getWaitingPeriodStart() : l.getEffectiveFrom(),
                    l.getLifeStatus()));
        }
        return lives;
    }

    private String principalKey(InsurancePolicy p) {
        return em.createQuery("select l.id from PolicyLifeCover l where l.policy = :p and"
                        + " l.relationship.selfRelationship = true order by l.id", Long.class)
                .setParameter("p", p).setMaxResults(1).getResultStream().findFirst()
                .map(id -> "LIFE-" + id).orElse("PRINCIPAL");
    }

    /** The insured life named (the main insured when none is named); anyone else is refused (section 07). */
    private Life life(InsurancePolicy p, String name) {
        List<Life> lives = lives(p);
        if (name == null) {
            return lives.get(0);
        }
        String wanted = normalise(name);
        return lives.stream().filter(l -> normalise(l.name()).equals(wanted)).findFirst()
                .orElseThrow(() -> new InvalidFieldException("lifeName", name + " is not insured on " + p.getRef()
                        + ". Insured: " + String.join(", ", lives.stream().map(Life::name).toList()) + "."));
    }

    /** The life key of an event; events recorded before keys existed are matched by name. */
    private String key(InsurancePolicy p, CareCashEvent e) {
        if (e.lifeKey != null) {
            return e.lifeKey;
        }
        String wanted = normalise(e.lifeName);
        return lives(p).stream().filter(l -> normalise(l.name()).equals(wanted)).map(Life::key).findFirst()
                .orElse("NAME-" + wanted);
    }

    private static String normalise(String text) {
        return text == null ? "" : String.join(" ", text.trim().toLowerCase(Locale.ROOT).split("\\s+"));
    }

    // ── lookups ──────────────────────────────────────────────────────────────────────────────────

    private List<CareCashEvent> events(String policyRef) {
        return em.createQuery("select e from CareCashEvent e where e.policyRef = :ref order by e.id desc",
                CareCashEvent.class).setParameter("ref", policyRef).getResultList();
    }

    private CareCashEvent event(String ref) {
        return em.createQuery("select e from CareCashEvent e where e.ref = :ref", CareCashEvent.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Care Cash event " + ref + " not found"));
    }

    private InsurancePolicy careCash(String policyRef) {
        InsurancePolicy p = workflow.policy(policyRef);
        if (!PRODUCT.equals(p.getProduct())) {
            throw new IllegalArgumentException(policyRef + " is a " + p.getProduct() + " policy, not Care Cash.");
        }
        return p;
    }

    private EventView view(CareCashEvent e) {
        return new EventView(e.ref, e.policyRef, e.customerName, e.lifeName, e.eventType, e.cause, e.admitDate,
                e.dischargeDate, e.icuDays, e.surgery, e.dailyBenefit, e.stayDays, e.paidDays, e.amount, e.waiverMonths,
                List.of(e.trace.split("\n")), e.status, e.createdBy, e.createdAt, e.decidedBy, e.decisionNote,
                e.paymentReference, e.paymentSentAt == null && e.paidAt == null ? null
                        : new Payment(e.payeeName, e.payoutMethod, e.payoutAccount, e.paymentSentBy, e.paymentSentAt,
                                PAID.equals(e.status) ? e.paidAt : null, e.paymentFailure),
                e.lifeKey, e.ruleVersion, e.preExisting == 1, e.births, e.linkedEventRef, e.daysNextYear,
                e.procedureName, e.deathEvidence, e.waiverFrom, e.waiverTo, e.reversedBy, e.reversedAt,
                e.reversalReason);
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    /** A date field, or a 400 naming the field (NS-API-001). */
    private static LocalDate date(JsonNode body, String field) {
        try {
            return LocalDate.parse(text(body, field));
        } catch (java.time.format.DateTimeParseException ex) {
            throw new InvalidFieldException(field, field + " must be a date like 2026-04-12.");
        }
    }

    private static String upper(String v) {
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    private static String clip(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }

    private static String text(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field)) {
            return null;
        }
        String v = body.get(field).asText().trim();
        return v.isEmpty() ? null : v;
    }
}
