package com.nsure.carecash;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.Money;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.portfolio.InsurancePolicy;
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
 * benefit: ward days, ICU days at 150% (15 a event), one surgical benefit, long-stay uplifts after 7 and 21 days, day
 * cases at 50%, maternity at five times the daily benefit (two per life, 12-month wait), accidental death for daily
 * tiers of 1,000 and above, and a three-month premium waiver after ten admission days. Day limits apply per event
 * (60), per life per policy year (180), per life for life (720) and across the policy (1,000). Every cap, count and
 * exclusion is written to the event's trace. The interpretation points the sign-off leaves open (decision D04) are
 * constants here so they are changed in one place.
 */
@Service
public class CareCashService {

    public static final String PRODUCT = "Care Cash";
    static final int QUALIFYING_NIGHTS = 2;
    static final int ICU_MAX_DAYS = 15;
    static final int EVENT_DAY_LIMIT = 60;
    static final int YEAR_DAY_LIMIT = 180;
    static final int LIFETIME_DAY_LIMIT = 720;
    static final int POLICY_DAY_LIMIT = 1000;
    static final int ILLNESS_WAIT_MONTHS = 3;
    static final int MATERNITY_WAIT_MONTHS = 12;
    static final int MATERNITY_MAX = 2;
    static final int WAIVER_AFTER_DAYS = 10;
    static final int WAIVER_MONTHS = 3;
    static final BigDecimal ACCIDENTAL_DEATH = new BigDecimal("25000.00");
    static final BigDecimal ACCIDENTAL_DEATH_MIN_TIER = new BigDecimal("1000");
    private static final Set<String> TYPES = Set.of("ADMISSION", "DAY_CASE", "MATERNITY", "ACCIDENTAL_DEATH");

    public record EventView(String ref, String policyRef, String customerName, String lifeName, String eventType,
            String cause, LocalDate admitDate, LocalDate dischargeDate, int icuDays, boolean surgery,
            BigDecimal dailyBenefit, int stayDays, int paidDays, BigDecimal amount, int waiverMonths, List<String> trace,
            String status, String createdBy, LocalDateTime createdAt, String decidedBy, String decisionNote,
            String paymentReference, Payment payment) {
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
    static final String PAYMENT_SENT = "PAYMENT_SENT";
    static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    static final String PAID = "PAID";

    public record Counters(String lifeName, int usedThisYear, int usedLifetime, int maternityClaims) {
    }

    public record PolicyView(String policyRef, String customerName, String status, BigDecimal dailyBenefit,
            LocalDate coverStartDate, int policyDaysUsed, int policyDaysLimit, List<Counters> lives,
            List<EventView> events, Map<String, Object> rules) {
    }

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final Outbox outbox;
    private final ReferenceNumbers numbers;
    private final EvidenceChain audit;
    private final WorkflowHistory history;
    private final PolicyWorkflow workflow;

    public CareCashService(EntityManager em, PlatformSecurityContext security, Outbox outbox, ReferenceNumbers numbers,
            EvidenceChain audit, WorkflowHistory history, PolicyWorkflow workflow) {
        this.em = em;
        this.security = security;
        this.outbox = outbox;
        this.numbers = numbers;
        this.audit = audit;
        this.history = history;
        this.workflow = workflow;
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
        List<Counters> lives = events.stream().map(e -> e.lifeName).distinct()
                .map(life -> new Counters(life, used(events, life, yearStart(p, today), null), used(events, life, null,
                        null), (int) events.stream().filter(e -> counts(e) && e.lifeName.equalsIgnoreCase(life)
                                && "MATERNITY".equals(e.eventType)).count()))
                .toList();
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("qualifyingNights", QUALIFYING_NIGHTS);
        rules.put("icuPercent", 150);
        rules.put("icuMaxDays", ICU_MAX_DAYS);
        rules.put("dayCasePercent", 50);
        rules.put("eventDayLimit", EVENT_DAY_LIMIT);
        rules.put("yearDayLimit", YEAR_DAY_LIMIT);
        rules.put("lifetimeDayLimit", LIFETIME_DAY_LIMIT);
        rules.put("policyDayLimit", POLICY_DAY_LIMIT);
        rules.put("illnessWaitMonths", ILLNESS_WAIT_MONTHS);
        rules.put("maternityWaitMonths", MATERNITY_WAIT_MONTHS);
        rules.put("maternityMaxClaims", MATERNITY_MAX);
        rules.put("waiverAfterDays", WAIVER_AFTER_DAYS);
        rules.put("waiverMonths", WAIVER_MONTHS);
        rules.put("accidentalDeath", ACCIDENTAL_DEATH);
        return new PolicyView(p.getRef(), p.getCustomerName(), p.getStatus(), p.getAmount(), p.getCoverStartDate(),
                used(events, null, null, null), POLICY_DAY_LIMIT, lives, events.stream().map(this::view).toList(), rules);
    }

    /** Works the event out without recording it. */
    @Transactional(readOnly = true)
    public EventView assess(String policyRef, JsonNode body) {
        AppUser user = user("CREATE_CLAIM");
        return view(calculate(careCash(policyRef), body, user.getUsername()));
    }

    /** {lifeName, eventType, cause, admitDate, dischargeDate?, icuDays?, surgery?}: records the assessed event. */
    @Transactional
    public EventView record(String policyRef, JsonNode body) {
        AppUser user = user("CREATE_CLAIM");
        InsurancePolicy p = careCash(policyRef);
        em.lock(p, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        CareCashEvent e = calculate(p, body, user.getUsername());
        e.ref = numbers.next("CCE-", 6, () -> 0);
        em.persist(e);
        outbox.publish(Events.BENEFIT_LIMIT_CONSUMED, e.ref, Map.of("policyRef", policyRef, "lifeName", e.lifeName,
                "days", e.paidDays, "eventRef", e.ref), user.getUsername());
        return view(e);
    }

    @Transactional(readOnly = true)
    public List<EventView> allEvents() {
        user("READ_CLAIM");
        return em.createQuery("select e from CareCashEvent e order by e.id desc", CareCashEvent.class).getResultList()
                .stream().map(this::view).toList();
    }

    /** {approve, note}: an approver other than the person who recorded the event. */
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
        e.status = approve ? "APPROVED" : "DECLINED";
        e.decidedBy = user.getUsername();
        e.decidedAt = LocalDateTime.now();
        e.decisionNote = note;
        if (approve && e.waiverMonths > 0) {
            // Premium waiver: the next premiums are not collected for the waiver period.
            InsurancePolicy p = workflow.policy(e.policyRef);
            if (p.getNextPremiumDue() != null) {
                p.setNextPremiumDue(p.getNextPremiumDue().plusMonths(e.waiverMonths));
                if (p.getGracePeriodEnd() != null) {
                    p.setGracePeriodEnd(p.getGracePeriodEnd().plusMonths(e.waiverMonths));
                }
            }
            history.record(WorkflowHistory.POLICY, p.getRef(), p.getCustomerName(), "Premium Waived", p.getStatus(),
                    p.getStatus(), e.waiverMonths + " months of premium waived after " + e.stayDays
                            + " admission days (" + ref + ")", user.getUsername());
        }
        audit.record(user.getUsername(), "Care Cash event " + e.status.toLowerCase(Locale.ROOT), "Policy", e.policyRef,
                ref + " " + Money.display(e.amount) + ": " + note);
        return view(e);
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
            throw new IllegalArgumentException("Give the payee, the payout method and the account or wallet number.");
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
        e.paymentReference = numbers.next("CCP-", 6, () -> 0);
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
        if (confirmed != null && !confirmed.equals(e.paymentReference)) {
            throw new IllegalArgumentException("This confirmation is for " + confirmed + ", but the payment in flight"
                    + " on " + ref + " is " + e.paymentReference + ".");
        }
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
        outbox.publish(Events.BENEFIT_PAID, e.ref, data, user.getUsername());
        audit.record(user.getUsername(), "Care Cash payment settled", "Policy", e.policyRef, ref + " "
                + Money.display(e.amount) + " · " + e.paymentReference + " · settlement confirmed");
        return view(e);
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
        e.status = PAYMENT_FAILED;
        e.paymentFailure = reason;
        audit.record(user.getUsername(), "Care Cash payment failed", "Policy", e.policyRef, ref + " · "
                + e.paymentReference + " · " + reason);
        return view(e);
    }

    private static String mask(String account) {
        return account == null || account.length() <= 4 ? account : "•••" + account.substring(account.length() - 4);
    }

    // ── the calculation ───────────────────────────────────────────────────────────────────────────

    private CareCashEvent calculate(InsurancePolicy p, JsonNode body, String actor) {
        String type = upper(text(body, "eventType"));
        String cause = upper(text(body, "cause"));
        if (type == null || !TYPES.contains(type)) {
            throw new IllegalArgumentException("eventType must be ADMISSION, DAY_CASE, MATERNITY or ACCIDENTAL_DEATH.");
        }
        cause = cause == null ? "ILLNESS" : cause;
        if (!Set.of("ILLNESS", "ACCIDENT").contains(cause)) {
            throw new IllegalArgumentException("cause must be ILLNESS or ACCIDENT.");
        }
        String life = text(body, "lifeName") == null ? p.getCustomerName() : text(body, "lifeName");
        if (text(body, "admitDate") == null) {
            throw new IllegalArgumentException("admitDate is required.");
        }
        LocalDate admit = LocalDate.parse(text(body, "admitDate"));
        LocalDate discharge = text(body, "dischargeDate") == null ? admit : LocalDate.parse(text(body, "dischargeDate"));
        if (discharge.isBefore(admit) || admit.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Check the dates: discharge cannot be before admission, and the"
                    + " admission cannot be in the future.");
        }
        BigDecimal daily = p.getAmount();
        List<String> trace = new ArrayList<>();
        CareCashEvent e = new CareCashEvent();
        e.policyRef = p.getRef();
        e.customerName = p.getCustomerName();
        e.lifeName = life;
        e.eventType = type;
        e.cause = cause;
        e.admitDate = admit;
        e.dischargeDate = discharge;
        e.icuDays = Math.max(0, body.path("icuDays").asInt(0));
        e.surgery = body.path("surgery").asBoolean(false);
        e.dailyBenefit = daily;
        e.amount = Money.of(BigDecimal.ZERO);
        e.status = "ASSESSED";
        e.createdBy = actor;
        e.createdAt = LocalDateTime.now();
        trace.add("Daily benefit " + Money.display(daily) + " on " + p.getRef() + ".");

        LocalDate start = p.getCoverStartDate();
        if (start == null || admit.isBefore(start) || !Set.of(PolicyWorkflow.ACTIVE, PolicyWorkflow.CLAIMED,
                PolicyWorkflow.TERMINATED).contains(p.getStatus()) && !PolicyWorkflow.ACTIVE.equals(p.getStatus())) {
            trace.add("Not payable: the policy was not in cover on " + admit + ".");
            return finish(e, trace);
        }
        // Waiting periods: accident immediate, illness 3 months, maternity 12 months.
        int wait = "MATERNITY".equals(type) ? MATERNITY_WAIT_MONTHS : "ACCIDENT".equals(cause) ? 0 : ILLNESS_WAIT_MONTHS;
        if (admit.isBefore(start.plusMonths(wait))) {
            trace.add("Not payable: within the " + wait + "-month waiting period for "
                    + ("MATERNITY".equals(type) ? "maternity" : "illness") + " (cover from " + start + ").");
            return finish(e, trace);
        }
        if (wait == 0) {
            trace.add("Accident: no waiting period.");
        }
        List<CareCashEvent> earlier = events(p.getRef());

        if ("ACCIDENTAL_DEATH".equals(type)) {
            if (!"ACCIDENT".equals(cause)) {
                trace.add("Not payable: the accidental death benefit needs an accidental cause.");
            } else if (daily.compareTo(ACCIDENTAL_DEATH_MIN_TIER) < 0) {
                trace.add("Not payable: accidental death applies to daily tiers of "
                        + Money.display(ACCIDENTAL_DEATH_MIN_TIER) + " and above.");
            } else {
                e.amount = ACCIDENTAL_DEATH;
                trace.add("Accidental death benefit " + Money.display(ACCIDENTAL_DEATH) + ".");
            }
            return finish(e, trace);
        }
        if ("MATERNITY".equals(type)) {
            long had = earlier.stream().filter(x -> counts(x) && "MATERNITY".equals(x.eventType)
                    && x.lifeName.equalsIgnoreCase(life) && x.amount.signum() > 0).count();
            if (had >= MATERNITY_MAX) {
                trace.add("Not payable: " + life + " has already had " + MATERNITY_MAX + " maternity claims.");
            } else {
                e.amount = Money.of(daily.multiply(BigDecimal.valueOf(5)));
                trace.add("Maternity: 5 × daily benefit = " + Money.display(e.amount) + " (claim " + (had + 1) + " of "
                        + MATERNITY_MAX + ").");
            }
            return finish(e, trace);
        }

        int remainingYear = YEAR_DAY_LIMIT - used(earlier, life, yearStart(p, admit), yearStart(p, admit).plusYears(1));
        int remainingLife = LIFETIME_DAY_LIMIT - used(earlier, life, null, null);
        int remainingPolicy = POLICY_DAY_LIMIT - used(earlier, null, null, null);
        if ("DAY_CASE".equals(type)) {
            e.stayDays = 1;
            int payable = Math.min(1, Math.max(0, Math.min(remainingYear, Math.min(remainingLife, remainingPolicy))));
            if (payable == 0) {
                trace.add("Not payable: a day limit is exhausted.");
                return finish(e, trace);
            }
            e.paidDays = 1;
            e.amount = Money.of(daily.multiply(new BigDecimal("0.5")));
            trace.add("Day case: 50% of the daily benefit = " + Money.display(e.amount) + "; counts 1 day against limits.");
            if (e.surgery) {
                e.amount = e.amount.add(daily);
                trace.add("Surgical benefit: 100% of the daily benefit, once per event = " + Money.display(daily) + ".");
            }
            return finish(e, trace);
        }

        int nights = (int) ChronoUnit.DAYS.between(admit, discharge);
        e.stayDays = nights;
        if (nights < QUALIFYING_NIGHTS) {
            trace.add("Not payable: " + nights + " night(s); an admission qualifies from " + QUALIFYING_NIGHTS
                    + " consecutive nights.");
            return finish(e, trace);
        }
        trace.add(nights + " nights from " + admit + " to " + discharge + ": qualifies, paid from day one.");
        int payable = nights;
        payable = cap(payable, EVENT_DAY_LIMIT, "60 days per event", trace);
        payable = cap(payable, remainingYear, "180 days per life per policy year (" + (YEAR_DAY_LIMIT - remainingYear)
                + " used)", trace);
        payable = cap(payable, remainingLife, "720 days per life (" + (LIFETIME_DAY_LIMIT - remainingLife) + " used)",
                trace);
        payable = cap(payable, remainingPolicy, "1,000 days across the policy (" + (POLICY_DAY_LIMIT - remainingPolicy)
                + " used)", trace);
        if (payable <= 0) {
            trace.add("Not payable: a day limit is exhausted.");
            return finish(e, trace);
        }
        e.paidDays = payable;
        int icu = Math.min(Math.min(e.icuDays, ICU_MAX_DAYS), payable);
        if (e.icuDays > ICU_MAX_DAYS) {
            trace.add("ICU days capped at " + ICU_MAX_DAYS + " per event (" + e.icuDays + " claimed).");
        }
        int ward = payable - icu;
        BigDecimal total = daily.multiply(BigDecimal.valueOf(ward));
        trace.add(ward + " ward day(s) × " + Money.display(daily) + " = " + Money.display(total) + ".");
        if (icu > 0) {
            BigDecimal icuAmount = daily.multiply(new BigDecimal("1.5")).multiply(BigDecimal.valueOf(icu));
            total = total.add(icuAmount);
            trace.add(icu + " ICU day(s) × 150% = " + Money.display(icuAmount) + " (instead of the ward rate, not on top).");
        }
        if (e.surgery) {
            total = total.add(daily);
            trace.add("Surgical benefit: 100% of the daily benefit, once per event = " + Money.display(daily) + ".");
        }
        if (nights > 7) {
            BigDecimal uplift = daily.multiply(BigDecimal.valueOf(3));
            total = total.add(uplift);
            trace.add("Long stay beyond 7 days: 3 × daily benefit = " + Money.display(uplift) + ".");
        }
        if (nights > 21) {
            BigDecimal uplift = daily.multiply(BigDecimal.valueOf(7));
            total = total.add(uplift);
            trace.add("Long stay beyond 21 days: a further 7 × daily benefit = " + Money.display(uplift) + ".");
        }
        if (nights >= WAIVER_AFTER_DAYS) {
            e.waiverMonths = WAIVER_MONTHS;
            trace.add("Premium waiver: " + WAIVER_MONTHS + " months, after " + WAIVER_AFTER_DAYS
                    + " or more consecutive admission days.");
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

    /** Days already used: a declined event gives its days back. */
    private static int used(List<CareCashEvent> events, String life, LocalDate from, LocalDate to) {
        return events.stream().filter(CareCashService::counts)
                .filter(e -> life == null || e.lifeName.equalsIgnoreCase(life))
                .filter(e -> from == null || !e.admitDate.isBefore(from))
                .filter(e -> to == null || e.admitDate.isBefore(to))
                .mapToInt(e -> e.paidDays).sum();
    }

    private static boolean counts(CareCashEvent e) {
        return !"DECLINED".equals(e.status);
    }

    /** The start of the policy year a date falls in: limits reset on the cover anniversary. */
    private static LocalDate yearStart(InsurancePolicy p, LocalDate date) {
        LocalDate start = p.getCoverStartDate() == null ? date : p.getCoverStartDate();
        long years = Math.max(0, ChronoUnit.YEARS.between(start, date));
        return start.plusYears(years);
    }

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
                                PAID.equals(e.status) ? e.paidAt : null, e.paymentFailure));
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    private static String upper(String v) {
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    private static String text(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field)) {
            return null;
        }
        String v = body.get(field).asText().trim();
        return v.isEmpty() ? null : v;
    }
}
