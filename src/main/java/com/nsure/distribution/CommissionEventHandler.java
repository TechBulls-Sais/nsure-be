package com.nsure.distribution;

import com.nsure.integration.DomainEvent;
import com.nsure.integration.DomainEventHandler;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Writes the commission ledger from events (SH06).
 *
 * <ul>
 *   <li><b>Accrual</b> (NS-SH06-003): a premium received accrues commission to the intermediary whose assignment was
 *       in force on the premium's date, at the rule in force then — the initial rate on the first premium, the renewal
 *       rate after — keeping the receipt, rule, agreement and a calculation trace. A premium that should have earned
 *       commission and did not (sold through an intermediary but never assigned, no rule, a product outside the
 *       agreement) opens a commission exception instead of disappearing.</li>
 *   <li><b>Clawback</b> (NS-SH06-005): a returned receipt takes back the commission that receipt earned
 *       (AT-SH06-01); a cancellation takes back every open accrual; a lapse within the agreement's clawback period
 *       (demo default 12 months) takes back the initial commission.</li>
 * </ul>
 * Each line carries its source event, so a redelivered event adds nothing.
 */
@Component
public class CommissionEventHandler implements DomainEventHandler {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String ACTOR = "system";

    private final EntityManager em;
    private final Outbox outbox;
    private final int defaultClawbackMonths;

    public CommissionEventHandler(EntityManager em, Outbox outbox,
            @Value("${nsure.distribution.clawback-months:12}") int defaultClawbackMonths) {
        this.em = em;
        this.outbox = outbox;
        this.defaultClawbackMonths = defaultClawbackMonths;
    }

    @Override
    public String name() {
        return "distribution.commission";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(Events.PREMIUM_RECEIVED, Events.POLICY_CANCELLED, Events.PAYMENT_RETURNED,
                Events.POLICY_TERMINATED);
    }

    @Override
    public void handle(DomainEvent event) {
        switch (event.eventType()) {
            case Events.PREMIUM_RECEIVED -> accrue(event);
            case Events.POLICY_CANCELLED -> clawBack(event);
            case Events.PAYMENT_RETURNED -> returned(event);
            case Events.POLICY_TERMINATED -> {
                if ("LAPSE".equals(event.text("reason"))) {
                    lapsed(event);
                }
            }
            default -> {
            }
        }
    }

    // ── accrual ──────────────────────────────────────────────────────────────────────────────────

    private void accrue(DomainEvent event) {
        if (exists(event.eventId()) || !em.createQuery("select x.id from CommissionException x"
                        + " where x.sourceEventId = :id", Long.class)
                .setParameter("id", event.eventId()).setMaxResults(1).getResultList().isEmpty()) {
            return;
        }
        Premium premium = new Premium(event.eventId(), event.text("policyRef"), event.text("product"),
                event.amount("amount").setScale(2, RoundingMode.HALF_UP),
                event.flag("firstPremium") ? Commission.INITIAL : Commission.RENEWAL, event.text("receiptRef"),
                event.occurredAt().toLocalDate());
        Outcome outcome = accrue(premium, event.correlationId());
        if (outcome.problemCode() != null) {
            CommissionException exception = new CommissionException(premium.policyRef(), premium.product(),
                    outcome.intermediaryRef(), premium.eventId(), premium.amount(), premium.basis(),
                    premium.receiptRef(), premium.date(), outcome.problemCode(), outcome.problem());
            em.persist(exception);
            em.flush();
            exception.numbered();
        }
    }

    record Premium(String eventId, String policyRef, String product, BigDecimal amount, String basis,
            String receiptRef, LocalDate date) {
    }

    /** The line written, or why none was: {@code problemCode} null with no line means nothing was due (direct). */
    record Outcome(Commission line, String problemCode, String problem, String intermediaryRef) {
    }

    /** Accrues one premium; shared by the event and by retrying a commission exception. */
    Outcome accrue(Premium premium, String correlationId) {
        Optional<PolicyAssignment> assignment = em.createQuery("select a from PolicyAssignment a"
                        + " join fetch a.intermediary where a.policyRef = :ref order by a.effectiveFrom desc, a.id desc",
                        PolicyAssignment.class)
                .setParameter("ref", premium.policyRef())
                .getResultList()
                .stream()
                .filter(a -> a.inForceOn(premium.date()))
                .findFirst();
        if (assignment.isEmpty()) {
            String soldBy = soldThrough(premium.policyRef());
            return soldBy == null ? new Outcome(null, null, null, null)
                    : new Outcome(null, CommissionException.NO_ASSIGNMENT, "Sold through intermediary " + soldBy
                            + " but the policy was not assigned to any intermediary on " + premium.date(), soldBy);
        }
        Intermediary intermediary = assignment.get().getIntermediary();
        if (premium.product() == null) {
            return new Outcome(null, CommissionException.NO_RULE, "The premium names no product", intermediary.getRef());
        }
        Agreement agreement = activeAgreement(em, intermediary, premium.date()).orElse(null);
        if (agreement != null && !agreement.covers(premium.product())) {
            return new Outcome(null, CommissionException.NOT_IN_AGREEMENT, "Agreement " + agreement.getRef() + " v"
                    + agreement.getVersion() + " of " + intermediary.getName() + " does not cover " + premium.product(),
                    intermediary.getRef());
        }
        Optional<CommissionRule> rule = ruleInForce(em, premium.product(), intermediary.getType(), premium.date());
        if (rule.isEmpty()) {
            return new Outcome(null, CommissionException.NO_RULE, "No commission rule for " + premium.product() + " and "
                    + intermediary.getType().toLowerCase() + "s in force on " + premium.date(), intermediary.getRef());
        }
        BigDecimal rate = Commission.INITIAL.equals(premium.basis()) ? rule.get().getInitialRatePercent()
                : rule.get().getRenewalRatePercent();
        BigDecimal amount = premium.amount().multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        String trace = rate.toPlainString() + "% " + premium.basis().toLowerCase() + " rate of rule #"
                + rule.get().getId() + " (" + premium.product() + ", " + intermediary.getType().toLowerCase()
                + ", from " + rule.get().getEffectiveFrom() + ") × collected premium BWP "
                + premium.amount().toPlainString() + " on " + premium.date()
                + (premium.receiptRef() == null ? "" : " (receipt " + premium.receiptRef() + ")")
                + (agreement == null ? "" : " under " + agreement.getRef() + " v" + agreement.getVersion())
                + " = BWP " + amount.toPlainString();
        Commission line = Commission.accrual(intermediary, premium.policyRef(), premium.product(), premium.eventId(),
                premium.amount(), rate, amount)
                .traced(premium.receiptRef(), rule.get().getId(), premium.basis(), trace, premium.date(),
                        agreement == null ? null : agreement.getRef());
        em.persist(line);
        em.flush();
        line.numbered();
        publish(Events.COMMISSION_ACCRUED, line, amount, correlationId);
        return new Outcome(line, null, null, intermediary.getRef());
    }

    /** The intermediary code an AGENT or BROKER application named, for the policy issued from it. */
    private String soldThrough(String policyRef) {
        List<Object[]> rows = em.createQuery("select a.channel, a.intermediaryCode from InsurancePolicy p,"
                        + " InsuranceApplication a where p.ref = :ref and a.ref = p.applicationRef", Object[].class)
                .setParameter("ref", policyRef).getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        Object channel = rows.get(0)[0];
        Object code = rows.get(0)[1];
        return code != null && !code.toString().isBlank() && ("AGENT".equals(channel) || "BROKER".equals(channel))
                ? code.toString() : null;
    }

    // ── clawbacks ────────────────────────────────────────────────────────────────────────────────

    /** AT-SH06-01: the returned receipt's own commission comes back, once. */
    private void returned(DomainEvent event) {
        String receiptRef = event.text("receiptRef");
        List<Commission> earned = receiptRef == null ? List.of() : em.createQuery("select c from Commission c"
                        + " join fetch c.intermediary where c.policyRef = :ref and c.kind = :accrual"
                        + " and c.receiptRef = :receipt"
                        + " and not exists (select r.id from Commission r where r.reverses = c)", Commission.class)
                .setParameter("ref", event.text("policyRef"))
                .setParameter("accrual", Commission.ACCRUAL)
                .setParameter("receipt", receiptRef)
                .getResultList();
        if (earned.isEmpty() && em.createQuery("select count(c) from Commission c where c.receiptRef = :receipt",
                Long.class).setParameter("receipt", receiptRef == null ? "" : receiptRef).getSingleResult() == 0) {
            // Commission accrued before receipts were recorded on lines: the latest open accrual on the policy.
            earned = em.createQuery("select c from Commission c join fetch c.intermediary where c.policyRef = :ref"
                            + " and c.kind = :accrual and c.receiptRef is null"
                            + " and not exists (select r.id from Commission r where r.reverses = c) order by c.id desc",
                            Commission.class)
                    .setParameter("ref", event.text("policyRef"))
                    .setParameter("accrual", Commission.ACCRUAL)
                    .setMaxResults(1)
                    .getResultList();
        }
        earned.forEach(original -> reverse(original, event));
    }

    private void clawBack(DomainEvent event) {
        open(event.text("policyRef")).forEach(original -> reverse(original, event));
    }

    /**
     * NS-SH06-005 lapse trigger: a policy that lapses within the clawback period of its first premium gives back the
     * initial commission (renewal commission is kept). The period is the agreement's, else the demo default.
     */
    private void lapsed(DomainEvent event) {
        LocalDate lapsedOn = date(event.text("terminatedOn"), event.occurredAt().toLocalDate());
        for (Commission original : open(event.text("policyRef"))) {
            if (!Commission.INITIAL.equals(original.getBasis())) {
                continue;
            }
            int months = defaultClawbackMonths;
            if (original.getAgreementRef() != null) {
                months = em.createQuery("select a.clawbackMonths from Agreement a where a.ref = :ref", Integer.class)
                        .setParameter("ref", original.getAgreementRef()).getResultStream().findFirst()
                        .orElse(defaultClawbackMonths);
            }
            LocalDate earnedOn = original.getPremiumDate() != null ? original.getPremiumDate()
                    : original.getCreatedAt().toLocalDate();
            if (!lapsedOn.isAfter(earnedOn.plusMonths(months))) {
                reverse(original, event);
            }
        }
    }

    private List<Commission> open(String policyRef) {
        return em.createQuery("select c from Commission c join fetch c.intermediary"
                        + " where c.policyRef = :ref and c.kind = :accrual"
                        + " and not exists (select r.id from Commission r where r.reverses = c) order by c.id asc",
                        Commission.class)
                .setParameter("ref", policyRef)
                .setParameter("accrual", Commission.ACCRUAL)
                .getResultList();
    }

    private void reverse(Commission original, DomainEvent event) {
        Commission line = Commission.clawback(original, event.eventId());
        if (exists(line.getSourceEventId())) {
            return;
        }
        em.persist(line);
        em.flush();
        line.numbered();
        publish(Events.COMMISSION_REVERSED, line, original.getAmount(), event.correlationId());
    }

    private void publish(String type, Commission line, BigDecimal amount, String correlationId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("commissionRef", line.getRef());
        data.put("intermediaryRef", line.getIntermediary().getRef());
        data.put("policyRef", line.getPolicyRef());
        data.put("product", line.getProduct());
        data.put("amount", amount.toPlainString());
        if (line.getReceiptRef() != null) {
            data.put("receiptRef", line.getReceiptRef());
        }
        outbox.publish(type, line.getRef(), data, ACTOR, correlationId);
    }

    private boolean exists(String sourceEventId) {
        return !em.createQuery("select c.id from Commission c where c.sourceEventId = :source", Long.class)
                .setParameter("source", sourceEventId)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    static Optional<CommissionRule> ruleInForce(EntityManager em, String product, String type, LocalDate day) {
        return em.createQuery("select r from CommissionRule r where r.product = :product and r.intermediaryType = :type"
                        + " and r.effectiveFrom <= :day order by r.effectiveFrom desc", CommissionRule.class)
                .setParameter("product", product)
                .setParameter("type", type)
                .setParameter("day", day)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    static Optional<Agreement> activeAgreement(EntityManager em, Intermediary intermediary, LocalDate day) {
        return em.createQuery("select a from Agreement a where a.intermediary = :i and a.status = :active"
                        + " and a.effectiveFrom <= :day order by a.version desc", Agreement.class)
                .setParameter("i", intermediary).setParameter("active", Agreement.ACTIVE).setParameter("day", day)
                .setMaxResults(1).getResultStream().findFirst();
    }

    private static LocalDate date(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            return fallback;
        }
    }
}
