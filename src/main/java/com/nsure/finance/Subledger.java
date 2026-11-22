package com.nsure.finance;

import com.nsure.integration.DomainEvent;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns business events into balanced journals: event → approved posting rule → journal (spec SH08 workflow). An
 * event with no usable rule becomes a visible financial exception instead of being dropped or blocking the business
 * transaction. The event id is the journal key, so a redelivered event returns the journal it already posted.
 */
@Service
public class Subledger {

    static final String SYSTEM = "system";

    /** NS-SH08-005: an event dated in a closed period posts to the next open one (keeping its date). */
    static final String LATE_NEXT_OPEN = "NEXT_OPEN_PERIOD";
    /** NS-SH08-005: an event dated in a closed period waits as a financial exception until Finance reopens it. */
    static final String LATE_EXCEPTION = "EXCEPTION";

    private final EntityManager em;
    private final Outbox outbox;
    private final String lateEvents;

    public Subledger(EntityManager em, Outbox outbox,
            @org.springframework.beans.factory.annotation.Value("${nsure.finance.late-events:NEXT_OPEN_PERIOD}")
            String lateEvents) {
        this.em = em;
        this.outbox = outbox;
        this.lateEvents = lateEvents == null ? LATE_NEXT_OPEN : lateEvents.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** SH08 JournalPosted: every journal written, including reversals and manual journals. */
    private void published(Journal journal, String actor, String correlationId) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("journalRef", journal.getRef());
        data.put("eventType", journal.getEventType());
        data.put("sourceRef", journal.getSourceRef());
        data.put("period", journal.getPeriodCode());
        data.put("accountingDate", journal.getAccountingDate().toString());
        data.put("amount", journal.getAmount().toPlainString());
        data.put("ruleVersion", journal.getRuleVersion());
        data.put("reverses", journal.getReversesRef() == null ? "" : journal.getReversesRef());
        outbox.publish(Events.JOURNAL_POSTED, journal.getRef(), data, actor, correlationId);
    }

    @Transactional
    public Optional<Journal> post(DomainEvent event, String actor) {
        Optional<Journal> existing = journalByKey(event.eventId());
        if (existing.isPresent()) {
            return existing;
        }
        LocalDate eventDate = event.occurredAt().toLocalDate();
        // The version in force on the day the business event happened, not today's (NS-SH08-001, NS-DAT-002), for
        // the event's product when a product-specific version exists.
        PostingRule rule = ruleInForce(event.eventType(), event.text("product"), eventDate).orElse(null);
        if (rule != null && rule.isNotPosted()) {
            // NS-SH08-002: not posted under the recognition basis in force; neither a journal nor an exception.
            return Optional.empty();
        }
        String problem = null;
        BigDecimal amount = BigDecimal.ZERO;
        if (rule == null || !rule.isActive()) {
            problem = "No active posting rule for " + event.eventType() + " on " + eventDate;
            amount = safeAmount(event, "amount");
        } else {
            amount = safeAmount(event, rule.getAmountField());
            if (!accountExists(rule.getDebitAccount()) || !accountExists(rule.getCreditAccount())) {
                problem = "Posting rule for " + event.eventType() + " names an unknown account";
            }
        }
        if (problem != null) {
            raise(event, amount, problem);
            return Optional.empty();
        }
        if (amount.signum() == 0) {
            return Optional.empty();
        }
        String debit = rule.getDebitAccount();
        String credit = rule.getCreditAccount();
        if (amount.signum() < 0) {
            // A negative business amount posts the other way round, so journal lines are always positive.
            debit = rule.getCreditAccount();
            credit = rule.getDebitAccount();
            amount = amount.negate();
        }
        AccountingPeriod period = postingPeriod(eventDate, actor);
        if (LATE_EXCEPTION.equals(lateEvents) && !period.getCode().equals(YearMonth.from(eventDate).toString())) {
            raise(event, amount, "Dated " + eventDate + " in closed period " + YearMonth.from(eventDate)
                    + ": waiting until Finance reopens it (late-event treatment EXCEPTION)");
            return Optional.empty();
        }
        LocalDate accountingDate = period.getCode().equals(YearMonth.from(eventDate).toString())
                ? eventDate
                : YearMonth.parse(period.getCode()).atDay(1);
        String description = rule.getDescription() + " · " + event.aggregateRef()
                + (accountingDate.equals(eventDate) ? "" : " · event " + eventDate + " posted to open period "
                        + period.getCode());
        Journal journal = new Journal(nextRef("JNL-", Journal.class), event.eventId(), event.eventType(),
                event.aggregateRef(), rule.getDomain(), eventDate, accountingDate, period.getCode(), amount,
                description, rule.getVersion(), rule.getId(), actor)
                // NS-SH08-001: policy, product, intermediary, treaty, scheme and cost centre dimensions.
                .dimensions(event.text("policyRef"), event.text("product"), event.text("intermediaryRef"),
                        event.text("treatyRef"), event.text("schemeRef"), rule.getCostCentre());
        journal.addLine(debit, amount, BigDecimal.ZERO);
        journal.addLine(credit, BigDecimal.ZERO, amount);
        if (!journal.isBalanced()) {
            throw new IllegalStateException("Journal for " + event.eventId() + " does not balance");
        }
        em.persist(journal);
        exceptionByKey(event.eventId()).ifPresent(open -> open.resolved(journal.getRef(), actor));
        published(journal, actor, event.correlationId());
        return Optional.of(journal);
    }

    /** A reversal: the original's lines swapped, in today's open period; the original is marked reversed. */
    @Transactional
    public Journal reverse(Journal original, String reason, String actor) {
        if (!Journal.POSTED.equals(original.getStatus())) {
            throw new IllegalArgumentException("Journal " + original.getRef() + " is already reversed.");
        }
        if (original.getReversesRef() != null) {
            throw new IllegalArgumentException("A reversal journal cannot itself be reversed; post a new event.");
        }
        LocalDate today = LocalDate.now();
        AccountingPeriod period = postingPeriod(today, actor);
        Journal reversal = new Journal(nextRef("JNL-", Journal.class), "REVERSAL:" + original.getRef(),
                original.getEventType(), original.getSourceRef(), original.getDomain(), original.getEventDate(),
                period.getCode().equals(YearMonth.from(today).toString()) ? today : YearMonth.parse(period.getCode()).atDay(1),
                period.getCode(), original.getAmount(), "Reversal of " + original.getRef() + " · " + reason,
                original.getRuleVersion(), original.getPostingRuleId(), actor)
                .dimensions(original.getPolicyRef(), original.getProduct(), original.getIntermediaryRef(),
                        original.getTreatyRef(), original.getSchemeRef(), original.getCostCentre());
        for (JournalLine line : original.getLines()) {
            reversal.addLine(line.getAccountCode(), line.getCredit(), line.getDebit());
        }
        reversal.markReversal(original.getRef());
        em.persist(reversal);
        original.markReversed(reversal.getRef());
        published(reversal, actor, null);
        return reversal;
    }

    /**
     * Posts an approved manual journal (Blueprint §7): the given lines, which must balance, dated in the period of
     * {@code accountingDate} or the next one that is not closed. Idempotent on the manual journal reference.
     */
    Journal postManual(String manualRef, String description, LocalDate accountingDate, List<BigDecimal[]> amounts,
            List<String> accounts, BigDecimal total, String actor) {
        Optional<Journal> existing = journalByKey("MANUAL:" + manualRef);
        if (existing.isPresent()) {
            return existing.get();
        }
        AccountingPeriod period = postingPeriod(accountingDate, actor);
        LocalDate posted = period.getCode().equals(YearMonth.from(accountingDate).toString())
                ? accountingDate : YearMonth.parse(period.getCode()).atDay(1);
        Journal journal = new Journal(nextRef("JNL-", Journal.class), "MANUAL:" + manualRef, "ManualJournal",
                manualRef, "SHARED", accountingDate, posted, period.getCode(), total,
                "Manual journal " + manualRef + " · " + description, 0, null, actor);
        for (int i = 0; i < accounts.size(); i++) {
            journal.addLine(accounts.get(i), amounts.get(i)[0], amounts.get(i)[1]);
        }
        if (!journal.isBalanced()) {
            throw new IllegalArgumentException("Manual journal " + manualRef + " does not balance.");
        }
        em.persist(journal);
        published(journal, actor, null);
        return journal;
    }

    /** The period an entry dated {@code date} posts to: its own, or the next one that is not closed. */
    AccountingPeriod postingPeriod(LocalDate date, String actor) {
        YearMonth month = YearMonth.from(date);
        for (int i = 0; i < 120; i++) {
            AccountingPeriod period = period(month.toString(), actor);
            if (period.acceptsPostings()) {
                return period;
            }
            month = month.plusMonths(1);
        }
        throw new IllegalStateException("No open accounting period after " + date);
    }

    AccountingPeriod period(String code, String actor) {
        List<AccountingPeriod> found = em.createQuery(
                        "select p from AccountingPeriod p where p.code = :code", AccountingPeriod.class)
                .setParameter("code", code)
                .getResultList();
        if (!found.isEmpty()) {
            return found.get(0);
        }
        AccountingPeriod created = new AccountingPeriod(code, actor);
        em.persist(created);
        return created;
    }

    /**
     * The approved version that applies on {@code date}: the latest effective on or before it. Versions never overlap,
     * because each one ends where the next approved one starts; on a shared start date the higher version wins.
     */
    Optional<PostingRule> ruleInForce(String eventType, LocalDate date) {
        return ruleInForce(eventType, null, date);
    }

    /** NS-SH08-001: a version for the event's product when one is in force, otherwise the general version. */
    Optional<PostingRule> ruleInForce(String eventType, String product, LocalDate date) {
        if (product != null && !product.isBlank()) {
            Optional<PostingRule> specific = em.createQuery("select r from PostingRule r where r.eventType = :type"
                            + " and r.product = :product and r.approvalStatus = :approved and r.effectiveFrom <= :date"
                            + " order by r.effectiveFrom desc, r.version desc", PostingRule.class)
                    .setParameter("type", eventType).setParameter("product", product)
                    .setParameter("approved", PostingRule.APPROVED).setParameter("date", date)
                    .setMaxResults(1).getResultStream().findFirst();
            if (specific.isPresent()) {
                return specific;
            }
        }
        return em.createQuery("select r from PostingRule r where r.eventType = :type and r.product is null"
                        + " and r.approvalStatus = :approved"
                        + " and r.effectiveFrom <= :date order by r.effectiveFrom desc, r.version desc",
                        PostingRule.class)
                .setParameter("type", eventType)
                .setParameter("approved", PostingRule.APPROVED)
                .setParameter("date", date)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    /**
     * NS-SH08-003: called while the business action is still open. When the rule for this event type is set to
     * BLOCK and the event could not post (inactive rule, or no amount), the action is refused instead of leaving a
     * financial exception behind.
     */
    void preflight(String eventType, java.util.Map<String, ?> data) {
        Object product = data == null ? null : data.get("product");
        PostingRule rule = ruleInForce(eventType, product == null ? null : product.toString(), LocalDate.now())
                .orElse(null);
        if (rule == null || !PostingRule.BLOCK.equals(rule.getFailureMode()) || rule.isNotPosted()) {
            return;
        }
        String problem = null;
        if (!rule.isActive()) {
            problem = "its posting rule v" + rule.getVersion() + " is inactive";
        } else if (!accountExists(rule.getDebitAccount()) || !accountExists(rule.getCreditAccount())) {
            problem = "its posting rule names an unknown account";
        } else {
            Object amount = data == null ? null : data.get(rule.getAmountField());
            try {
                new BigDecimal(String.valueOf(amount));
            } catch (NumberFormatException ex) {
                problem = "it carries no " + rule.getAmountField();
            }
        }
        if (problem != null) {
            // Not an IllegalArgumentException: callers keep work done before one of those; this must undo everything.
            throw new com.nsure.common.exception.CommandFailedException(422, null, eventType + " cannot be posted ("
                    + problem + ") and Finance set this event type to block the business action until it can."
                    + " Nothing was saved.");
        }
    }

    /** Every version of every rule, newest first within each event type. */
    List<PostingRule> ruleVersions() {
        return em.createQuery("select r from PostingRule r order by r.eventType, r.version desc", PostingRule.class)
                .getResultList();
    }

    List<PostingRule> ruleVersions(String eventType) {
        return em.createQuery("select r from PostingRule r where r.eventType = :type order by r.version desc",
                        PostingRule.class)
                .setParameter("type", eventType)
                .getResultList();
    }

    boolean accountExists(String code) {
        return em.createQuery("select count(a) from LedgerAccount a where a.code = :code", Long.class)
                .setParameter("code", code)
                .getSingleResult() > 0;
    }

    Optional<Journal> journalByKey(String key) {
        return em.createQuery("select j from Journal j where j.eventKey = :key", Journal.class)
                .setParameter("key", key)
                .getResultStream()
                .findFirst();
    }

    Optional<UnpostedEvent> exceptionByKey(String key) {
        return em.createQuery("select e from UnpostedEvent e where e.eventKey = :key", UnpostedEvent.class)
                .setParameter("key", key)
                .getResultStream()
                .findFirst();
    }

    private void raise(DomainEvent event, BigDecimal amount, String reason) {
        Optional<UnpostedEvent> open = exceptionByKey(event.eventId());
        if (open.isPresent()) {
            open.get().stillFailing(reason);
        } else {
            em.persist(new UnpostedEvent(event.eventId(), event.eventType(), event.aggregateRef(), amount.abs(),
                    reason));
            // SH08: raised once, when the exception opens; retries that still fail update it quietly.
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("eventKey", event.eventId());
            data.put("eventType", event.eventType());
            data.put("sourceRef", event.aggregateRef());
            data.put("amount", amount.abs().toPlainString());
            data.put("reason", reason.length() <= 300 ? reason : reason.substring(0, 300));
            outbox.publish(Events.FINANCIAL_EXCEPTION_RAISED, "UNPOSTED-" + event.eventId(), data, SYSTEM,
                    event.correlationId());
        }
    }

    private static BigDecimal safeAmount(DomainEvent event, String field) {
        try {
            return event.amount(field).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    <T> String nextRef(String prefix, Class<T> type) {
        Long max = em.createQuery("select max(x.id) from " + type.getSimpleName() + " x", Long.class)
                .getSingleResult();
        return String.format("%s%05d", prefix, (max == null ? 0 : max) + 1);
    }
}
