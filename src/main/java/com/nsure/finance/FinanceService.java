package com.nsure.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.DomainEvent;
import com.nsure.integration.IntegrationService;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Finance workspace: enquiries, posting rules, reversals, exceptions, period close and GL export (SH08). */
@Service
public class FinanceService {

    private static final Set<String> DOMAINS = Set.of("INDIVIDUAL", "GROUP", "SHARED");

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final Subledger subledger;
    private final IntegrationService integration;

    public FinanceService(EntityManager em, PlatformSecurityContext security, Subledger subledger,
            IntegrationService integration) {
        this.em = em;
        this.security = security;
        this.subledger = subledger;
        this.integration = integration;
    }

    // ── enquiries ─────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<FinanceViews.AccountView> accounts() {
        reader();
        return accountList().stream()
                .map(a -> new FinanceViews.AccountView(a.getCode(), a.getName(), a.getAccountType()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FinanceViews.RuleView> rules() {
        reader();
        return em.createQuery("select r from PostingRule r order by r.id", PostingRule.class).getResultList()
                .stream().map(FinanceService::rule).toList();
    }

    @Transactional(readOnly = true)
    public List<FinanceViews.JournalView> journals(String period, String eventType) {
        reader();
        StringBuilder jpql = new StringBuilder("select j from Journal j where 1 = 1");
        if (period != null && !period.isBlank()) {
            jpql.append(" and j.periodCode = :period");
        }
        if (eventType != null && !eventType.isBlank()) {
            jpql.append(" and j.eventType = :type");
        }
        var query = em.createQuery(jpql.append(" order by j.id desc").toString(), Journal.class).setMaxResults(500);
        if (period != null && !period.isBlank()) {
            query.setParameter("period", period);
        }
        if (eventType != null && !eventType.isBlank()) {
            query.setParameter("type", eventType);
        }
        Map<String, String> names = accountNames();
        return query.getResultList().stream().map(j -> journal(j, names)).toList();
    }

    @Transactional(readOnly = true)
    public FinanceViews.JournalView journal(String ref) {
        reader();
        return journal(journalByRef(ref), accountNames());
    }

    @Transactional(readOnly = true)
    public List<FinanceViews.PeriodView> periods() {
        reader();
        return em.createQuery("select p from AccountingPeriod p order by p.code desc", AccountingPeriod.class)
                .getResultList().stream().map(this::period).toList();
    }

    @Transactional(readOnly = true)
    public List<FinanceViews.ExceptionView> exceptions() {
        reader();
        return em.createQuery("select e from UnpostedEvent e order by e.status asc, e.id desc", UnpostedEvent.class)
                .getResultList().stream()
                .map(e -> new FinanceViews.ExceptionView(e.getId(), e.getEventKey(), e.getEventType(),
                        e.getSourceRef(), e.getAmount(), e.getReason(), e.getStatus(), e.getCreatedAt(),
                        e.getResolvedBy(), e.getResolvedAt(), e.getJournalRef()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FinanceViews.GlBatchView> glBatches() {
        reader();
        return em.createQuery("select b from GlBatch b order by b.id desc", GlBatch.class).getResultList()
                .stream().map(FinanceService::batch).toList();
    }

    /** Debits and credits per account, all periods or one. Balanced by construction; shown so it can be proven. */
    @Transactional(readOnly = true)
    public FinanceViews.TrialBalanceView trialBalance(String period) {
        reader();
        boolean one = period != null && !period.isBlank();
        var query = em.createQuery("select l.accountCode, sum(l.debit), sum(l.credit) from JournalLine l"
                + (one ? " where l.journal.periodCode = :period" : "") + " group by l.accountCode", Object[].class);
        if (one) {
            query.setParameter("period", period);
        }
        Map<String, BigDecimal[]> sums = new LinkedHashMap<>();
        for (Object[] row : query.getResultList()) {
            sums.put((String) row[0], new BigDecimal[] {(BigDecimal) row[1], (BigDecimal) row[2]});
        }
        List<FinanceViews.TrialBalanceRow> rows = new ArrayList<>();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (LedgerAccount account : accountList()) {
            BigDecimal[] s = sums.getOrDefault(account.getCode(), new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal d = s[0] == null ? BigDecimal.ZERO : s[0];
            BigDecimal c = s[1] == null ? BigDecimal.ZERO : s[1];
            debit = debit.add(d);
            credit = credit.add(c);
            rows.add(new FinanceViews.TrialBalanceRow(account.getCode(), account.getName(), account.getAccountType(),
                    d, c, d.subtract(c)));
        }
        return new FinanceViews.TrialBalanceView(one ? period : "ALL", rows, debit, credit,
                debit.compareTo(credit) == 0, LocalDateTime.now());
    }

    /** Headline figures for the management dashboard (READ_REPORT is checked by the caller). */
    @Transactional(readOnly = true)
    public FinanceViews.FinanceSummary summary() {
        return new FinanceViews.FinanceSummary(
                count("select count(j) from Journal j"),
                net("4000", false).add(net("4100", false)),
                net("5000", true),
                net("5100", true),
                net("5200", true),
                net("2000", false),
                count("select count(e) from UnpostedEvent e where e.status = 'OPEN'"),
                count("select count(j) from Journal j where j.glBatchRef is null"),
                count("select count(b) from GlBatch b where b.status = 'SUBMITTED'"),
                LocalDateTime.now());
    }

    // ── commands ──────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public FinanceViews.RuleView updateRule(String eventType, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_FINANCE");
        String debit = required(body, "debitAccount");
        String credit = required(body, "creditAccount");
        if (debit.equals(credit)) {
            throw new IllegalArgumentException("Debit and credit accounts must differ.");
        }
        for (String code : List.of(debit, credit)) {
            if (!subledger.accountExists(code)) {
                throw new IllegalArgumentException("Account " + code + " is not in the chart of accounts.");
            }
        }
        String domain = text(body, "domain", "SHARED").toUpperCase(Locale.ROOT);
        if (!DOMAINS.contains(domain)) {
            throw new IllegalArgumentException("Domain must be INDIVIDUAL, GROUP or SHARED.");
        }
        String status = text(body, "status", PostingRule.ACTIVE).toUpperCase(Locale.ROOT);
        if (!Set.of(PostingRule.ACTIVE, PostingRule.INACTIVE).contains(status)) {
            throw new IllegalArgumentException("Status must be ACTIVE or INACTIVE.");
        }
        PostingRule rule = subledger.rule(eventType);
        if (rule == null) {
            rule = new PostingRule(eventType);
            em.persist(rule);
        }
        rule.change(debit, credit, text(body, "amountField", "amount"), domain,
                text(body, "description", eventType), status, user.getUsername());
        return rule(rule);
    }

    @Transactional
    public FinanceViews.JournalView reverse(String ref, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_FINANCE");
        String reason = required(body, "reason");
        Journal reversal = subledger.reverse(journalByRef(ref), reason, user.getUsername());
        return journal(reversal, accountNames());
    }

    /** Posts an exception's event again, after Finance has fixed the posting rule. */
    @Transactional
    public FinanceViews.ExceptionView retry(Long id) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_FINANCE");
        UnpostedEvent exception = em.find(UnpostedEvent.class, id);
        if (exception == null) {
            throw new NotFoundException("Financial exception " + id + " not found");
        }
        if (UnpostedEvent.RESOLVED.equals(exception.getStatus())) {
            throw new ConflictException("This exception is already resolved by journal " + exception.getJournalRef());
        }
        DomainEvent event = integration.domainEvent(exception.getEventKey())
                .orElseThrow(() -> new NotFoundException("Event " + exception.getEventKey() + " not found"));
        subledger.post(event, user.getUsername());
        return exceptions().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public FinanceViews.PeriodView periodAction(String code, String action, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("CLOSE_PERIOD");
        try {
            YearMonth.parse(code);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Period must be yyyy-MM.");
        }
        AccountingPeriod period = subledger.period(code, user.getUsername());
        String status = period.getStatus();
        String note = text(body, "reason", null);
        switch (action) {
            case "soft-close" -> {
                if (!AccountingPeriod.OPEN.equals(status)) {
                    throw new IllegalArgumentException("Only an open period can be soft-closed; " + code + " is " + status + ".");
                }
                period.moveTo(AccountingPeriod.SOFT_CLOSED, note, user.getUsername());
            }
            case "close" -> {
                if (!AccountingPeriod.SOFT_CLOSED.equals(status)) {
                    throw new IllegalArgumentException("Soft-close " + code + " before closing it.");
                }
                List<String> open = closeBlockers(code);
                boolean disposed = body != null && body.path("acknowledgeOpenItems").asBoolean(false);
                if (!open.isEmpty() && !disposed) {
                    throw new ConflictException("Period " + code + " has open items: " + String.join("; ", open)
                            + ". Resolve them, or close with acknowledgeOpenItems and a reason.");
                }
                if (!open.isEmpty() && (note == null || note.isBlank())) {
                    throw new IllegalArgumentException("Give a reason for closing with open items.");
                }
                period.moveTo(AccountingPeriod.CLOSED, open.isEmpty() ? note
                        : (note + " · closed with: " + String.join("; ", open)), user.getUsername());
            }
            case "reopen" -> {
                if (AccountingPeriod.OPEN.equals(status)) {
                    throw new IllegalArgumentException("Period " + code + " is already open.");
                }
                if (note == null || note.isBlank()) {
                    throw new IllegalArgumentException("Give a reason for reopening the period.");
                }
                period.moveTo(AccountingPeriod.OPEN, note, user.getUsername());
            }
            default -> throw new IllegalArgumentException("Unknown period action " + action);
        }
        return period(period);
    }

    /** Unposted events, journals not yet exported, and exported batches the GL has not acknowledged (AT-SH08-03). */
    List<String> closeBlockers(String code) {
        List<String> open = new ArrayList<>();
        long exceptions = count("select count(e) from UnpostedEvent e where e.status = 'OPEN'");
        if (exceptions > 0) {
            open.add(exceptions + " unposted event(s)");
        }
        long unexported = em.createQuery("select count(j) from Journal j where j.periodCode = :p and j.glBatchRef is null",
                Long.class).setParameter("p", code).getSingleResult();
        if (unexported > 0) {
            open.add(unexported + " journal(s) not exported to the GL");
        }
        long pending = em.createQuery("select count(distinct b.id) from GlBatch b, Journal j where j.glBatchRef = b.ref"
                        + " and j.periodCode = :p and b.status = 'SUBMITTED'", Long.class)
                .setParameter("p", code).getSingleResult();
        if (pending > 0) {
            open.add(pending + " GL batch(es) awaiting acknowledgement");
        }
        return open;
    }

    @Transactional
    public FinanceViews.GlBatchView exportToGl() {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("EXPORT_GL");
        List<Journal> journals = em.createQuery("select j from Journal j where j.glBatchRef is null order by j.id",
                Journal.class).getResultList();
        if (journals.isEmpty()) {
            throw new IllegalArgumentException("No journals are waiting for export.");
        }
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        int lines = 0;
        for (Journal journal : journals) {
            for (JournalLine line : journal.getLines()) {
                debit = debit.add(line.getDebit());
                credit = credit.add(line.getCredit());
                lines++;
            }
        }
        if (debit.compareTo(credit) != 0) {
            throw new IllegalStateException("Export batch does not balance");
        }
        GlBatch batch = new GlBatch(subledger.nextRef("GLB-", GlBatch.class), journals.size(), lines, debit, credit,
                user.getUsername());
        em.persist(batch);
        journals.forEach(j -> j.assignGlBatch(batch.getRef()));
        return batch(batch);
    }

    /** The GL's answer. Repeating the same answer is harmless; a rejected batch's journals go out again next time. */
    @Transactional
    public FinanceViews.GlBatchView acknowledge(String ref, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("EXPORT_GL");
        GlBatch batch = em.createQuery("select b from GlBatch b where b.ref = :ref", GlBatch.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("GL batch " + ref + " not found"));
        String status = required(body, "status").toUpperCase(Locale.ROOT);
        if (!Set.of(GlBatch.ACCEPTED, GlBatch.REJECTED).contains(status)) {
            throw new IllegalArgumentException("Status must be ACCEPTED or REJECTED.");
        }
        if (!GlBatch.SUBMITTED.equals(batch.getStatus())) {
            if (batch.getStatus().equals(status)) {
                return batch(batch);
            }
            throw new ConflictException("GL batch " + ref + " is already " + batch.getStatus() + ".");
        }
        String note = text(body, "note", null);
        if (GlBatch.REJECTED.equals(status) && (note == null || note.isBlank())) {
            throw new IllegalArgumentException("Record the GL's rejection reason.");
        }
        batch.acknowledge(status, text(body, "glReference", null), note, user.getUsername());
        if (GlBatch.REJECTED.equals(status)) {
            em.createQuery("select j from Journal j where j.glBatchRef = :ref", Journal.class)
                    .setParameter("ref", ref).getResultList().forEach(j -> j.assignGlBatch(null));
        }
        return batch(batch);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private void reader() {
        security.authenticatedUser().validateHasPermissionTo("READ_FINANCE");
    }

    private List<LedgerAccount> accountList() {
        return em.createQuery("select a from LedgerAccount a order by a.displayOrder", LedgerAccount.class)
                .getResultList();
    }

    private Map<String, String> accountNames() {
        Map<String, String> names = new LinkedHashMap<>();
        accountList().forEach(a -> names.put(a.getCode(), a.getName()));
        return names;
    }

    private Journal journalByRef(String ref) {
        return em.createQuery("select j from Journal j where j.ref = :ref", Journal.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Journal " + ref + " not found"));
    }

    private FinanceViews.PeriodView period(AccountingPeriod p) {
        long journals = em.createQuery("select count(j) from Journal j where j.periodCode = :p", Long.class)
                .setParameter("p", p.getCode()).getSingleResult();
        long unexported = em.createQuery(
                        "select count(j) from Journal j where j.periodCode = :p and j.glBatchRef is null", Long.class)
                .setParameter("p", p.getCode()).getSingleResult();
        return new FinanceViews.PeriodView(p.getCode(), p.getStatus(), p.getNote(), p.getUpdatedBy(),
                p.getUpdatedAt(), journals, unexported);
    }

    private long count(String jpql) {
        return em.createQuery(jpql, Long.class).getSingleResult();
    }

    /** Net movement on an account: debit minus credit for debit-natured accounts, else credit minus debit. */
    private BigDecimal net(String account, boolean debitNature) {
        Object[] row = em.createQuery("select sum(l.debit), sum(l.credit) from JournalLine l where l.accountCode = :a",
                Object[].class).setParameter("a", account).getSingleResult();
        BigDecimal d = row[0] == null ? BigDecimal.ZERO : (BigDecimal) row[0];
        BigDecimal c = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
        return debitNature ? d.subtract(c) : c.subtract(d);
    }

    private static FinanceViews.RuleView rule(PostingRule r) {
        return new FinanceViews.RuleView(r.getEventType(), r.getDebitAccount(), r.getCreditAccount(),
                r.getAmountField(), r.getDomain(), r.getDescription(), r.getStatus(), r.getVersion(),
                r.getUpdatedBy(), r.getUpdatedAt());
    }

    private static FinanceViews.JournalView journal(Journal j, Map<String, String> names) {
        return new FinanceViews.JournalView(j.getRef(), j.getEventType(), j.getSourceRef(), j.getDomain(),
                j.getEventDate(), j.getAccountingDate(), j.getPeriodCode(), j.getCurrency(), j.getAmount(),
                j.getDescription(), j.getRuleVersion(), j.getStatus(), j.getReversesRef(), j.getReversedByRef(),
                j.getGlBatchRef(), j.getCreatedBy(), j.getCreatedAt(),
                j.getLines().stream().map(l -> new FinanceViews.LineView(l.getLineNo(), l.getAccountCode(),
                        names.getOrDefault(l.getAccountCode(), l.getAccountCode()), l.getDebit(), l.getCredit()))
                        .toList());
    }

    private static FinanceViews.GlBatchView batch(GlBatch b) {
        return new FinanceViews.GlBatchView(b.getRef(), b.getStatus(), b.getJournalCount(), b.getLineCount(),
                b.getDebitTotal(), b.getCreditTotal(), b.getCreatedBy(), b.getCreatedAt(), b.getGlReference(),
                b.getAcknowledgedBy(), b.getAcknowledgedAt(), b.getNote());
    }

    private static String required(JsonNode body, String field) {
        String value = text(body, field, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value.trim();
    }

    private static String text(JsonNode body, String field, String fallback) {
        if (body == null || !body.hasNonNull(field) || body.get(field).asText().isBlank()) {
            return fallback;
        }
        return body.get(field).asText().trim();
    }
}
