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
import java.time.LocalDate;
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
    private final com.nsure.audit.evidence.EvidenceChain evidence;
    private final com.nsure.integration.Outbox outbox;
    private final CorporateGl gl;

    public FinanceService(EntityManager em, PlatformSecurityContext security, Subledger subledger,
            IntegrationService integration, com.nsure.audit.evidence.EvidenceChain evidence,
            com.nsure.integration.Outbox outbox, CorporateGl gl) {
        this.gl = gl;
        this.outbox = outbox;
        this.em = em;
        this.security = security;
        this.subledger = subledger;
        this.integration = integration;
        this.evidence = evidence;
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
        Map<String, List<PostingRule>> byType = new LinkedHashMap<>();
        subledger.ruleVersions().forEach(r -> byType.computeIfAbsent(r.getEventType(), t -> new ArrayList<>()).add(r));
        List<FinanceViews.RuleView> views = new ArrayList<>();
        byType.values().forEach(versions -> views.addAll(ruleViews(versions)));
        return views;
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

    /**
     * Proposes the next version of a posting rule (NS-SH08-001). Nothing changes until a different user approves it,
     * and then only for events dated on or after its effective date; journals already posted keep their version.
     * {@code baseVersion}, when sent, is the version the user edited: a newer one makes this a 409, not an overwrite.
     */
    @Transactional
    public FinanceViews.RuleView proposeRule(String eventType, JsonNode body) {
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
        if (!Set.of(PostingRule.ACTIVE, PostingRule.INACTIVE, PostingRule.NOT_POSTED).contains(status)) {
            throw new IllegalArgumentException("Status must be ACTIVE, INACTIVE or NOT_POSTED.");
        }
        String failureMode = text(body, "failureMode", PostingRule.CONTINUE).toUpperCase(Locale.ROOT);
        if (!Set.of(PostingRule.CONTINUE, PostingRule.BLOCK).contains(failureMode)) {
            throw new IllegalArgumentException("failureMode must be CONTINUE or BLOCK.");
        }
        String product = text(body, "product", null);
        if (product != null && em.createQuery("select count(p) from InsuranceProduct p where p.name = :name", Long.class)
                .setParameter("name", product).getSingleResult() == 0) {
            throw new IllegalArgumentException("Unknown product " + product + ".");
        }
        String costCentre = text(body, "costCentre", null);
        if (costCentre != null && costCentre.length() > 20) {
            throw new IllegalArgumentException("costCentre is at most 20 characters.");
        }
        String reason = required(body, "reason");
        if (reason.length() > 500) {
            throw new IllegalArgumentException("Keep the reason to 500 characters.");
        }
        LocalDate effective = date(body, "effectiveFrom", LocalDate.now());
        List<PostingRule> versions = subledger.ruleVersions(eventType);
        versions.stream().filter(PostingRule::isPending).findFirst().ifPresent(pending -> {
            throw new ConflictException(eventType + " v" + pending.getVersion() + " is awaiting approval."
                    + " Approve or reject it before proposing another change.");
        });
        int latest = versions.isEmpty() ? 0 : versions.get(0).getVersion();
        if (body.hasNonNull("baseVersion") && body.get("baseVersion").asInt() != latest) {
            throw new ConflictException(eventType + " changed to v" + latest + " while you were editing v"
                    + body.get("baseVersion").asInt() + ". Reload to see the current rule.");
        }
        requireValidStart(eventType, effective, null);
        PostingRule current = subledger.ruleInForce(eventType, product, effective).orElse(null);
        PostingRule next = new PostingRule(eventType, latest + 1, debit, credit, text(body, "amountField", "amount"),
                domain, text(body, "description", eventType), status, effective, reason, user.getUsername())
                .scoped(product, costCentre == null && current != null ? current.getCostCentre() : costCentre,
                        failureMode);
        em.persist(next);
        evidence.record(user.getUsername(), "Proposed posting rule", "PostingRule", eventType + " v" + next.getVersion(),
                (current == null ? "(none)" : "v" + current.getVersion() + " " + current.summary()) + " → "
                        + next.summary() + ", effective " + effective
                        + (effective.isBefore(LocalDate.now()) ? " (backdated)" : "") + ". " + reason);
        em.flush();
        return ruleView(next);
    }

    /** A different user approves a proposed version; it applies from its effective date (Blueprint §7, D07). */
    @Transactional
    public FinanceViews.RuleView approveRule(String eventType, int version) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("APPROVE_JOURNAL");
        PostingRule rule = pendingRule(eventType, version, user);
        // Re-checked now: journals may have posted since it was proposed, making its start date a backdate.
        requireValidStart(eventType, rule.getEffectiveFrom(), rule.getId());
        rule.approve(user.getUsername());
        evidence.record(user.getUsername(), "Approved posting rule", "PostingRule", eventType + " v" + version,
                rule.summary() + ", effective " + rule.getEffectiveFrom() + ", proposed by " + rule.getProposedBy());
        em.flush();
        return ruleView(rule);
    }

    @Transactional
    public FinanceViews.RuleView rejectRule(String eventType, int version, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("APPROVE_JOURNAL");
        PostingRule rule = pendingRule(eventType, version, user);
        String reason = required(body, "reason");
        rule.reject(user.getUsername(), reason.length() <= 500 ? reason : reason.substring(0, 500));
        evidence.record(user.getUsername(), "Rejected posting rule", "PostingRule", eventType + " v" + version, reason);
        em.flush();
        return ruleView(rule);
    }

    private PostingRule pendingRule(String eventType, int version, AppUser checker) {
        PostingRule rule = ruleVersion(eventType, version);
        if (!rule.isPending()) {
            throw new ConflictException(eventType + " v" + version + " is "
                    + rule.getApprovalStatus().toLowerCase(Locale.ROOT).replace('_', ' ') + ".");
        }
        if (checker.getUsername().equalsIgnoreCase(rule.getProposedBy())) {
            throw new com.nsure.security.exception.NoAuthorizationException("You proposed " + eventType + " v"
                    + version + ", so another user must approve or reject it (maker-checker).");
        }
        return rule;
    }

    private static LocalDate date(JsonNode body, String field, LocalDate fallback) {
        String value = text(body, field, null);
        if (value == null) {
            return fallback;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(field + " must be a date (yyyy-MM-dd).");
        }
    }

    @Transactional
    public FinanceViews.JournalView reverse(String ref, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_FINANCE");
        String reason = required(body, "reason");
        Journal original = journalByRef(ref);
        com.nsure.common.ExpectedVersion.check("Journal " + ref, original.getRowVersion());
        Journal reversal = subledger.reverse(original, reason, user.getUsername());
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
                Map<String, Object> closed = new java.util.LinkedHashMap<>();
                closed.put("period", code);
                closed.put("closedBy", user.getUsername());
                closed.put("openItems", String.join("; ", open));
                outbox.publish(com.nsure.integration.Events.PERIOD_CLOSED, "PERIOD-" + code, closed,
                        user.getUsername());
            }
            case "reopen" -> {
                // NS-SH08-005: reopening is asked for here and authorised by someone else (approve-reopen).
                if (AccountingPeriod.OPEN.equals(status)) {
                    throw new IllegalArgumentException("Period " + code + " is already open.");
                }
                if (note == null || note.isBlank()) {
                    throw new IllegalArgumentException("Give a reason for reopening the period.");
                }
                if (period.getReopenRequestedBy() != null) {
                    throw new ConflictException("Reopening " + code + " is already waiting for authorisation.");
                }
                period.requestReopen(note.length() <= 500 ? note : note.substring(0, 500), user.getUsername());
            }
            default -> throw new IllegalArgumentException("Unknown period action " + action);
        }
        return period(period);
    }

    /**
     * NS-SH08-005: Finance authority (APPROVE_JOURNAL) reopens a period someone else asked to reopen; or declines.
     * Late events then post into it with their own date again.
     */
    @Transactional
    public FinanceViews.PeriodView decideReopen(String code, boolean approve) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("APPROVE_JOURNAL");
        AccountingPeriod period = subledger.period(code, user.getUsername());
        if (period.getReopenRequestedBy() == null) {
            throw new ConflictException("Nobody has asked to reopen " + code + ".");
        }
        if (user.getUsername().equalsIgnoreCase(period.getReopenRequestedBy())) {
            throw new com.nsure.security.exception.NoAuthorizationException("You asked to reopen " + code
                    + "; someone else must authorise it.");
        }
        String reason = period.getReopenReason();
        String asker = period.getReopenRequestedBy();
        period.clearReopen();
        if (approve) {
            period.moveTo(AccountingPeriod.OPEN, "Reopened: " + reason + " (asked by " + asker + ", authorised by "
                    + user.getUsername() + ")", user.getUsername());
            evidence.record(user.getUsername(), "Reopened accounting period", "AccountingPeriod", code, reason);
        }
        return period(period);
    }

    /** AT-SH08-03: every open item that stands in the way of closing a period, by category. */
    @Transactional(readOnly = true)
    public List<FinanceViews.CloseItem> closeReport(String code) {
        reader();
        return closeItems(code);
    }

    /** Unposted events, journals not yet exported, and exported batches the GL has not acknowledged (AT-SH08-03). */
    List<String> closeBlockers(String code) {
        return closeItems(code).stream().filter(i -> i.count() > 0).map(i -> i.count() + " " + i.label()).toList();
    }

    private List<FinanceViews.CloseItem> closeItems(String code) {
        YearMonth month = YearMonth.parse(code);
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
        List<FinanceViews.CloseItem> items = new ArrayList<>();
        // Unposted events of business dated in this period (not every open exception everywhere).
        List<String> unposted = em.createQuery("select e.eventType || ' ' || e.sourceRef from UnpostedEvent e,"
                        + " OutboxEvent o where o.eventId = e.eventKey and e.status = 'OPEN' and o.occurredAt >= :from"
                        + " and o.occurredAt < :to", String.class)
                .setParameter("from", from).setParameter("to", to).setMaxResults(20).getResultList();
        long unpostedCount = em.createQuery("select count(e) from UnpostedEvent e, OutboxEvent o where o.eventId ="
                        + " e.eventKey and e.status = 'OPEN' and o.occurredAt >= :from and o.occurredAt < :to", Long.class)
                .setParameter("from", from).setParameter("to", to).getSingleResult();
        items.add(new FinanceViews.CloseItem("UNPOSTED", "unposted event(s)", unpostedCount, unposted));
        List<String> unexported = em.createQuery("select j.ref from Journal j where j.periodCode = :p"
                + " and j.glBatchRef is null order by j.id", String.class).setParameter("p", code).setMaxResults(20)
                .getResultList();
        long unexportedCount = em.createQuery("select count(j) from Journal j where j.periodCode = :p"
                + " and j.glBatchRef is null", Long.class).setParameter("p", code).getSingleResult();
        items.add(new FinanceViews.CloseItem("UNEXPORTED", "journal(s) not exported to the GL", unexportedCount,
                unexported));
        List<String> pending = em.createQuery("select distinct b.ref from GlBatch b, Journal j where j.glBatchRef ="
                        + " b.ref and j.periodCode = :p and b.status in ('PREPARED', 'SUBMITTED')", String.class)
                .setParameter("p", code).getResultList();
        items.add(new FinanceViews.CloseItem("GL_PENDING", "GL batch(es) awaiting acknowledgement", pending.size(),
                pending));
        List<String> rejected = em.createQuery("select b.ref from GlBatch b where b.status = 'REJECTED'"
                        + " and b.periodCodes like :p", String.class)
                .setParameter("p", "%" + code + "%").getResultList().stream()
                .filter(r -> unexportedCount > 0).toList();
        items.add(new FinanceViews.CloseItem("GL_REJECTED", "GL batch(es) rejected by the GL, journals not yet re-sent",
                rejected.size(), rejected));
        List<String> unreconciled = em.createQuery("select distinct b.ref from GlBatch b, Journal j where j.glBatchRef ="
                        + " b.ref and j.periodCode = :p and b.status = 'ACCEPTED'", String.class)
                .setParameter("p", code).getResultList();
        items.add(new FinanceViews.CloseItem("GL_UNRECONCILED", "accepted GL batch(es) not yet reconciled",
                unreconciled.size(), unreconciled));
        // Unreconciled cash: open bank-statement exceptions and receipts still in suspense from this period.
        List<String> recon = em.createQuery("select concat(i.status, ' ', i.providerRef) from ReconItem i where"
                        + " i.status <> 'MATCHED' and i.resolvedAt is null and i.valueDate >= :d1 and i.valueDate < :d2",
                        String.class)
                .setParameter("d1", month.atDay(1)).setParameter("d2", month.plusMonths(1).atDay(1)).setMaxResults(20)
                .getResultList();
        items.add(new FinanceViews.CloseItem("CASH_RECON", "open bank reconciliation item(s)", recon.size(), recon));
        List<String> suspense = em.createQuery("select m.providerRef from InboundMessage m where m.status = 'SUSPENSE'"
                        + " and m.receivedAt >= :from and m.receivedAt < :to", String.class)
                .setParameter("from", from).setParameter("to", to).setMaxResults(20).getResultList();
        items.add(new FinanceViews.CloseItem("SUSPENSE", "receipt(s) still in suspense", suspense.size(), suspense));
        long manual = count("select count(m) from ManualJournal m where m.status = 'PENDING'");
        items.add(new FinanceViews.CloseItem("MANUAL", "manual journal(s) awaiting approval", manual, List.of()));
        return items;
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
        batch.periods(String.join(",", journals.stream().map(Journal::getPeriodCode).distinct().sorted().toList()));
        em.persist(batch);
        journals.forEach(j -> j.assignGlBatch(batch.getRef()));
        return batch(batch);
    }

    /** Sends a prepared batch to the corporate GL (Blueprint §7: prepared → submitted). */
    @Transactional
    public FinanceViews.GlBatchView submit(String ref) {
        return submit(ref, null);
    }

    /**
     * Prepared → submitted. With {@code deliver} (body {"simulate": ACCEPT|REJECT|TIMEOUT}) the export file goes to the
     * corporate GL interface and its answer is applied at once; without one, staff key the GL's answer later. A batch
     * already submitted whose answer was lost is never sent blind: check its status by batch id (AT-SH08-02).
     */
    @Transactional
    public FinanceViews.GlBatchView submit(String ref, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("EXPORT_GL");
        GlBatch batch = glBatch(ref);
        boolean deliver = body != null && body.has("simulate");
        if (!GlBatch.PREPARED.equals(batch.getStatus())) {
            if (GlBatch.SUBMITTED.equals(batch.getStatus())) {
                if (deliver) {
                    throw new ConflictException("GL batch " + ref + " was already sent and no answer was recorded. Check"
                            + " its status with the GL by batch id before sending anything again.");
                }
                return batch(batch);
            }
            throw new ConflictException("GL batch " + ref + " is already " + batch.getStatus() + ".");
        }
        batch.submit(user.getUsername());
        if (!deliver) {
            return batch(batch);
        }
        CorporateGl.Ack ack = gl.send(exportFile(batch), text(body, "simulate", "ACCEPT"));
        if (ack == null) {
            batch.delivery("Sent to " + gl.name() + "; no answer (timeout). Check its status by batch id before"
                    + " sending it again.");
            return batch(batch);
        }
        applyAck(batch, ack, user.getUsername());
        return batch(batch);
    }

    /**
     * AT-SH08-02: asks the GL what it holds under the batch id. Posted → accepted with the GL's reference (nothing is
     * sent again); rejected → rejected; never received → back to prepared, safe to send again.
     */
    @Transactional
    public FinanceViews.GlBatchView statusCheck(String ref) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("EXPORT_GL");
        GlBatch batch = glBatch(ref);
        if (!GlBatch.SUBMITTED.equals(batch.getStatus())) {
            throw new ConflictException("GL batch " + ref + " is " + batch.getStatus() + "; only a submitted batch"
                    + " waits for the GL's answer.");
        }
        CorporateGl.Ack ack = gl.status(ref);
        if ("UNKNOWN".equals(ack.status())) {
            batch.backToPrepared("Checked by batch id: the GL never received " + ref + "; it can be sent again.");
            return batch(batch);
        }
        applyAck(batch, ack, user.getUsername());
        return batch(batch);
    }

    /** The batch's export file in the agreed format (batch and line ids, control totals). */
    @Transactional(readOnly = true)
    public CorporateGl.ExportFile exportFile(String ref) {
        reader();
        return exportFile(glBatch(ref));
    }

    private CorporateGl.ExportFile exportFile(GlBatch batch) {
        List<CorporateGl.ExportLine> lines = new ArrayList<>();
        int n = 0;
        for (Journal j : em.createQuery("select j from Journal j where j.glBatchRef = :ref order by j.id", Journal.class)
                .setParameter("ref", batch.getRef()).getResultList()) {
            for (JournalLine l : j.getLines()) {
                lines.add(new CorporateGl.ExportLine(batch.getRef() + "-" + String.format("%04d", ++n), j.getRef(),
                        j.getEventType(), j.getAccountingDate().toString(), j.getPeriodCode(), l.getAccountCode(),
                        l.getDebit(), l.getCredit(), j.getCurrency(), j.getCostCentre(), j.getPolicyRef(),
                        j.getProduct(), j.getIntermediaryRef(), j.getTreatyRef(), j.getSchemeRef(), j.getDescription()));
            }
        }
        return new CorporateGl.ExportFile(batch.getRef(), batch.getJournalCount(), batch.getLineCount(),
                batch.getDebitTotal(), batch.getCreditTotal(), "BWP", lines);
    }

    private void applyAck(GlBatch batch, CorporateGl.Ack ack, String actor) {
        batch.delivery(gl.name() + " answered " + ack.status() + " (delivery " + ack.deliveries() + ")"
                + (ack.reason() == null ? "" : ": " + ack.reason()));
        if ("POSTED".equals(ack.status())) {
            acknowledged(batch, GlBatch.ACCEPTED, ack.glReference(), "Acknowledged by " + gl.name(), actor);
        } else if ("REJECTED".equals(ack.status())) {
            acknowledged(batch, GlBatch.REJECTED, null, ack.reason() == null ? "Rejected by the GL" : ack.reason(), actor);
        }
    }

    /**
     * Reconciles an accepted batch: the totals the GL reports as posted must equal the batch control totals
     * (Blueprint §7: accepted subledger totals reconcile to the GL). A difference is refused and stays visible.
     */
    @Transactional
    public FinanceViews.GlBatchView reconcile(String ref, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("EXPORT_GL");
        GlBatch batch = glBatch(ref);
        if (!GlBatch.ACCEPTED.equals(batch.getStatus())) {
            throw new ConflictException("Only a batch the GL accepted can be reconciled; " + ref + " is "
                    + batch.getStatus() + ".");
        }
        BigDecimal glDebit;
        BigDecimal glCredit;
        if (text(body, "glDebitTotal", null) == null && text(body, "glCreditTotal", null) == null) {
            // NS-SH08-006: the totals the GL itself reports as posted under this batch id.
            CorporateGl.Ack held = gl.status(ref);
            if (held.debitTotal() == null) {
                throw new IllegalArgumentException("The GL reports nothing posted for " + ref + "; enter its totals.");
            }
            glDebit = held.debitTotal().setScale(2, java.math.RoundingMode.HALF_UP);
            glCredit = held.creditTotal().setScale(2, java.math.RoundingMode.HALF_UP);
        } else {
            try {
                glDebit = new BigDecimal(required(body, "glDebitTotal")).setScale(2, java.math.RoundingMode.HALF_UP);
                glCredit = new BigDecimal(required(body, "glCreditTotal")).setScale(2, java.math.RoundingMode.HALF_UP);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("GL totals must be numbers.");
            }
        }
        if (glDebit.compareTo(batch.getDebitTotal()) != 0 || glCredit.compareTo(batch.getCreditTotal()) != 0) {
            throw new ConflictException("The GL totals (debit " + glDebit.toPlainString() + ", credit "
                    + glCredit.toPlainString() + ") do not match batch " + ref + " (debit "
                    + batch.getDebitTotal().toPlainString() + ", credit " + batch.getCreditTotal().toPlainString()
                    + "). Investigate the difference before reconciling.");
        }
        batch.reconcile(glDebit, glCredit, user.getUsername());
        return batch(batch);
    }

    private GlBatch glBatch(String ref) {
        return em.createQuery("select b from GlBatch b where b.ref = :ref", GlBatch.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("GL batch " + ref + " not found"));
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
        if (GlBatch.PREPARED.equals(batch.getStatus())) {
            throw new ConflictException("GL batch " + ref + " has not been submitted to the GL yet.");
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
        acknowledged(batch, status, text(body, "glReference", null), note, user.getUsername());
        return batch(batch);
    }

    private void acknowledged(GlBatch batch, String status, String glReference, String note, String actor) {
        String ref = batch.getRef();
        batch.acknowledge(status, glReference, note, actor);
        if (GlBatch.ACCEPTED.equals(status)) {
            Map<String, Object> accepted = new java.util.LinkedHashMap<>();
            accepted.put("batchRef", batch.getRef());
            accepted.put("glReference", batch.getGlReference() == null ? "" : batch.getGlReference());
            accepted.put("journalCount", batch.getJournalCount());
            accepted.put("debitTotal", batch.getDebitTotal().toPlainString());
            accepted.put("creditTotal", batch.getCreditTotal().toPlainString());
            outbox.publish(com.nsure.integration.Events.GL_BATCH_ACCEPTED, batch.getRef(), accepted, actor);
        }
        if (GlBatch.REJECTED.equals(status)) {
            em.createQuery("select j from Journal j where j.glBatchRef = :ref", Journal.class)
                    .setParameter("ref", ref).getResultList().forEach(j -> j.assignGlBatch(null));
        }
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
                p.getUpdatedAt(), journals, unexported, p.getReopenRequestedBy(), p.getReopenRequestedAt(),
                p.getReopenReason());
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

    /** Views of one event type's versions (newest first), with each approved version's last day and today's rule. */
    private List<FinanceViews.RuleView> ruleViews(List<PostingRule> versions) {
        LocalDate today = LocalDate.now();
        List<PostingRule> approved = versions.stream().filter(PostingRule::isApproved).toList();
        Long inForce = approved.stream()
                .filter(r -> !r.getEffectiveFrom().isAfter(today))
                .max(java.util.Comparator.comparing(PostingRule::getEffectiveFrom)
                        .thenComparingInt(PostingRule::getVersion))
                .map(PostingRule::getId).orElse(null);
        List<FinanceViews.RuleView> views = new ArrayList<>();
        for (PostingRule r : versions) {
            LocalDate to = null;
            if (r.isApproved()) {
                to = approved.stream()
                        .filter(n -> n.getEffectiveFrom().isAfter(r.getEffectiveFrom())
                                || (n.getEffectiveFrom().equals(r.getEffectiveFrom()) && n.getVersion() > r.getVersion()))
                        .map(n -> n.getEffectiveFrom().minusDays(1))
                        .min(LocalDate::compareTo).orElse(null);
            }
            views.add(new FinanceViews.RuleView(r.getId(), r.getEventType(), r.getDebitAccount(), r.getCreditAccount(),
                    r.getAmountField(), r.getDomain(), r.getDescription(), r.getStatus(), r.getVersion(),
                    r.getEffectiveFrom(), to, r.getApprovalStatus(), r.getId().equals(inForce), r.getChangeReason(),
                    r.getProposedBy(), r.getRecordedAt(), r.getApprovedBy(), r.getApprovedAt(), r.getRejectionReason(),
                    r.getProduct(), r.getCostCentre(), r.getFailureMode()));
        }
        return views;
    }

    private FinanceViews.RuleView ruleView(PostingRule rule) {
        return ruleViews(subledger.ruleVersions(rule.getEventType())).stream()
                .filter(v -> v.id().equals(rule.getId())).findFirst().orElseThrow();
    }

    private PostingRule ruleVersion(String eventType, int version) {
        return subledger.ruleVersions(eventType).stream().filter(r -> r.getVersion() == version).findFirst()
                .orElseThrow(() -> new NotFoundException("Posting rule " + eventType + " v" + version + " not found"));
    }

    /**
     * A version may not start before an approved one (versions take effect in order), and a backdated version may not
     * start on or before a day already posted for its event type: a posted day keeps the rule it was posted under.
     */
    private void requireValidStart(String eventType, LocalDate effective, Long ignoreId) {
        LocalDate latestApproved = subledger.ruleVersions(eventType).stream()
                .filter(r -> r.isApproved() && !r.getId().equals(ignoreId))
                .map(PostingRule::getEffectiveFrom).max(LocalDate::compareTo).orElse(null);
        if (latestApproved != null && effective.isBefore(latestApproved)) {
            throw new IllegalArgumentException("A new version of " + eventType + " cannot start before " + latestApproved
                    + ", when the latest approved version starts.");
        }
        if (effective.isBefore(LocalDate.now())) {
            LocalDate lastPosted = em.createQuery("select max(j.eventDate) from Journal j where j.eventType = :type"
                            + " and j.reversesRef is null", LocalDate.class)
                    .setParameter("type", eventType).getSingleResult();
            if (lastPosted != null && !effective.isAfter(lastPosted)) {
                throw new IllegalArgumentException(eventType + " events up to " + lastPosted + " are already posted"
                        + " under earlier versions. A backdated version can start no earlier than "
                        + lastPosted.plusDays(1) + ".");
            }
        }
    }

    private static FinanceViews.JournalView journal(Journal j, Map<String, String> names) {
        return new FinanceViews.JournalView(j.getRef(), j.getEventType(), j.getSourceRef(), j.getDomain(),
                j.getEventDate(), j.getAccountingDate(), j.getPeriodCode(), j.getCurrency(), j.getAmount(),
                j.getDescription(), j.getRuleVersion(), j.getPostingRuleId(), j.getStatus(), j.getReversesRef(),
                j.getReversedByRef(),
                j.getGlBatchRef(), j.getCreatedBy(), j.getCreatedAt(),
                j.getLines().stream().map(l -> new FinanceViews.LineView(l.getLineNo(), l.getAccountCode(),
                        names.getOrDefault(l.getAccountCode(), l.getAccountCode()), l.getDebit(), l.getCredit()))
                        .toList(), j.getRowVersion(), j.getPolicyRef(), j.getProduct(), j.getIntermediaryRef(),
                j.getTreatyRef(), j.getSchemeRef(), j.getCostCentre());
    }

    private static FinanceViews.GlBatchView batch(GlBatch b) {
        return new FinanceViews.GlBatchView(b.getRef(), b.getStatus(), b.getJournalCount(), b.getLineCount(),
                b.getDebitTotal(), b.getCreditTotal(), b.getCreatedBy(), b.getCreatedAt(), b.getGlReference(),
                b.getAcknowledgedBy(), b.getAcknowledgedAt(), b.getNote(), b.getSubmittedBy(), b.getSubmittedAt(),
                b.getReconciledBy(), b.getReconciledAt(), b.getPeriodCodes(), b.getDeliveryNote());
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
