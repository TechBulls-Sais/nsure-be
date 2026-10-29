package com.nsure.finance;

import com.nsure.integration.DomainEvent;
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

    private final EntityManager em;

    public Subledger(EntityManager em) {
        this.em = em;
    }

    @Transactional
    public Optional<Journal> post(DomainEvent event, String actor) {
        Optional<Journal> existing = journalByKey(event.eventId());
        if (existing.isPresent()) {
            return existing;
        }
        PostingRule rule = rule(event.eventType());
        String problem = null;
        BigDecimal amount = BigDecimal.ZERO;
        if (rule == null || !rule.isActive()) {
            problem = "No active posting rule for " + event.eventType();
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
        LocalDate eventDate = event.occurredAt().toLocalDate();
        AccountingPeriod period = postingPeriod(eventDate, actor);
        LocalDate accountingDate = period.getCode().equals(YearMonth.from(eventDate).toString())
                ? eventDate
                : YearMonth.parse(period.getCode()).atDay(1);
        String description = rule.getDescription() + " · " + event.aggregateRef()
                + (accountingDate.equals(eventDate) ? "" : " · event " + eventDate + " posted to open period "
                        + period.getCode());
        Journal journal = new Journal(nextRef("JNL-", Journal.class), event.eventId(), event.eventType(),
                event.aggregateRef(), rule.getDomain(), eventDate, accountingDate, period.getCode(), amount,
                description, rule.getVersion(), actor);
        journal.addLine(debit, amount, BigDecimal.ZERO);
        journal.addLine(credit, BigDecimal.ZERO, amount);
        if (!journal.isBalanced()) {
            throw new IllegalStateException("Journal for " + event.eventId() + " does not balance");
        }
        em.persist(journal);
        exceptionByKey(event.eventId()).ifPresent(open -> open.resolved(journal.getRef(), actor));
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
                original.getRuleVersion(), actor);
        for (JournalLine line : original.getLines()) {
            reversal.addLine(line.getAccountCode(), line.getCredit(), line.getDebit());
        }
        reversal.markReversal(original.getRef());
        em.persist(reversal);
        original.markReversed(reversal.getRef());
        return reversal;
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

    PostingRule rule(String eventType) {
        return em.createQuery("select r from PostingRule r where r.eventType = :type", PostingRule.class)
                .setParameter("type", eventType)
                .getResultStream()
                .findFirst()
                .orElse(null);
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
