package com.nsure.collections;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.InboundMessage;
import com.nsure.payment.PaymentTransaction;
import com.nsure.portfolio.ReferenceNumbers;
import com.nsure.security.domain.AppUser;
import com.nsure.security.repository.AppUserRepository;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles a provider or bank statement to the receipts recorded here (NS-SH04-006). Every statement line is
 * matched by provider reference, then checked for amount and currency; receipts the statement does not show are
 * listed too. Exceptions stay open with an owner and an age until someone resolves them with a note.
 */
@Service
public class StatementReconciliation {

    static final String STATEMENT = "STATEMENT";
    static final String SYSTEM = "SYSTEM";
    private static final int MAX_LINES = 2000;

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final ReferenceNumbers references;
    private final AppUserRepository users;

    public StatementReconciliation(EntityManager em, PlatformSecurityContext security, ReferenceNumbers references,
            AppUserRepository users) {
        this.em = em;
        this.security = security;
        this.references = references;
        this.users = users;
    }

    /** {provider, periodFrom, periodTo, lines: [{reference, amount, currency?, valueDate?}]} */
    @Transactional
    public CollectionsViews.StatementView upload(JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_FINANCE");
        String provider = text(body, "provider");
        if (provider == null || provider.length() > 40) {
            throw new IllegalArgumentException("provider is required (at most 40 characters).");
        }
        LocalDate from = date(body, "periodFrom", null);
        LocalDate to = date(body, "periodTo", null);
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("periodFrom and periodTo are required, and the period must not end before it starts.");
        }
        JsonNode lines = body.get("lines");
        if (lines == null || !lines.isArray()) {
            throw new IllegalArgumentException("lines must be a list of statement lines.");
        }
        if (lines.size() > MAX_LINES) {
            throw new IllegalArgumentException("A statement can have at most " + MAX_LINES + " lines.");
        }
        Statement statement = new Statement(references.next("STM-", 6, () -> 0L), provider, from, to,
                user.getUsername());
        em.persist(statement);

        Set<String> seen = new HashSet<>();
        Set<String> matchedTransactions = new HashSet<>();
        Set<Long> matchedInbound = new HashSet<>();
        int lineNo = 0;
        for (JsonNode line : lines) {
            lineNo++;
            String ref = text(line, "reference");
            if (ref == null) {
                throw new IllegalArgumentException("Line " + lineNo + " has no reference.");
            }
            BigDecimal amount = amount(line, lineNo);
            String currency = text(line, "currency") == null ? "BWP" : text(line, "currency").toUpperCase(Locale.ROOT);
            LocalDate valueDate = date(line, "valueDate", to);
            if (!seen.add(ref)) {
                em.persist(new ReconItem(statement, STATEMENT, lineNo, ref, amount, currency, valueDate,
                        ReconItem.DUPLICATE, null, null, null, user.getUsername()));
                continue;
            }
            PaymentTransaction tx = em.createQuery("select t from PaymentTransaction t where lower(t.provider) ="
                            + " :provider and t.providerReference = :ref", PaymentTransaction.class)
                    .setParameter("provider", provider.toLowerCase(Locale.ROOT)).setParameter("ref", ref)
                    .getResultStream().findFirst().orElse(null);
            if (tx != null) {
                matchedTransactions.add(tx.getProviderReference());
                // A statement line is not matched on amount alone: money for an attempt closed as failed or expired,
                // or not yet confirmed here, is a late settlement (NS-SH04-006).
                String outcome = Set.of(PaymentTransaction.FAILED, PaymentTransaction.EXPIRED, PaymentTransaction.CREATED,
                        PaymentTransaction.REDIRECTED, PaymentTransaction.PENDING_VERIFICATION).contains(tx.getStatus())
                        ? ReconItem.LATE_SETTLEMENT : compare(amount, currency, tx.getAmount(), tx.getCurrency());
                em.persist(new ReconItem(statement, STATEMENT, lineNo, ref, amount, currency, valueDate,
                        outcome, "PAYMENT_TRANSACTION", tx.getProviderReference(), tx.getAmount(), user.getUsername()));
                continue;
            }
            CollectionInstruction instruction = em.createQuery("select c from CollectionInstruction c join c.batch b"
                            + " where lower(b.provider) = :provider and c.providerReference = :ref",
                            CollectionInstruction.class)
                    .setParameter("provider", provider.toLowerCase(Locale.ROOT)).setParameter("ref", ref)
                    .getResultStream().findFirst().orElse(null);
            if (instruction != null) {
                // A debit order the bank answered as unpaid (or never answered) but whose money arrived after all.
                em.persist(new ReconItem(statement, STATEMENT, lineNo, ref, amount, currency, valueDate,
                        ReconItem.LATE_SETTLEMENT, "COLLECTION_INSTRUCTION", instruction.getRef(),
                        instruction.getAmount(), user.getUsername()));
                continue;
            }
            InboundMessage inbound = em.createQuery("select m from InboundMessage m where lower(m.provider) = :provider"
                            + " and m.providerRef = :ref", InboundMessage.class)
                    .setParameter("provider", provider.toLowerCase(Locale.ROOT)).setParameter("ref", ref)
                    .getResultStream().findFirst().orElse(null);
            if (inbound != null) {
                matchedInbound.add(inbound.getId());
                em.persist(new ReconItem(statement, STATEMENT, lineNo, ref, amount, currency, valueDate,
                        compare(amount, currency, inbound.getAmount(), "BWP"), "INBOUND_RECEIPT",
                        inbound.getProviderRef(), inbound.getAmount(), user.getUsername()));
                continue;
            }
            em.persist(new ReconItem(statement, STATEMENT, lineNo, ref, amount, currency, valueDate,
                    ReconItem.MISSING_RECEIPT, null, null, null, user.getUsername()));
        }

        // Receipts recorded here in the period that the statement does not show.
        for (PaymentTransaction tx : em.createQuery("select t from PaymentTransaction t where lower(t.provider) ="
                        + " :provider and t.status in :settled and t.settledAt >= :from and t.settledAt < :to",
                        PaymentTransaction.class)
                .setParameter("provider", provider.toLowerCase(Locale.ROOT))
                .setParameter("settled", Set.of(PaymentTransaction.SETTLED, PaymentTransaction.RETURNED,
                        PaymentTransaction.REVERSED))
                .setParameter("from", from.atStartOfDay()).setParameter("to", to.plusDays(1).atStartOfDay())
                .getResultList()) {
            if (!matchedTransactions.contains(tx.getProviderReference())) {
                em.persist(new ReconItem(statement, SYSTEM, null, tx.getProviderReference(), tx.getAmount(),
                        tx.getCurrency(), tx.getSettledAt().toLocalDate(), ReconItem.NOT_ON_STATEMENT,
                        "PAYMENT_TRANSACTION", tx.getProviderReference(), tx.getAmount(), user.getUsername()));
            }
        }
        for (InboundMessage m : em.createQuery("select m from InboundMessage m where lower(m.provider) = :provider"
                        + " and m.receivedAt >= :from and m.receivedAt < :to", InboundMessage.class)
                .setParameter("provider", provider.toLowerCase(Locale.ROOT))
                .setParameter("from", from.atStartOfDay()).setParameter("to", to.plusDays(1).atStartOfDay())
                .getResultList()) {
            if (!matchedInbound.contains(m.getId())) {
                em.persist(new ReconItem(statement, SYSTEM, null, m.getProviderRef(), m.getAmount(), "BWP",
                        m.getReceivedAt().toLocalDate(), ReconItem.NOT_ON_STATEMENT, "INBOUND_RECEIPT",
                        m.getProviderRef(), m.getAmount(), user.getUsername()));
            }
        }
        em.flush();
        return view(statement);
    }

    @Transactional(readOnly = true)
    public List<CollectionsViews.StatementView> statements() {
        security.authenticatedUser().validateHasPermissionTo("READ_FINANCE");
        return em.createQuery("select s from Statement s order by s.id desc", Statement.class)
                .setMaxResults(100).getResultStream().map(this::view).toList();
    }

    /** Reconciliation items: the open exceptions, oldest first; or every item of one statement. */
    @Transactional(readOnly = true)
    public List<CollectionsViews.ReconItemView> items(String statementRef, boolean openOnly) {
        security.authenticatedUser().validateHasPermissionTo("READ_FINANCE");
        String jpql = "select i from ReconItem i join fetch i.statement s where "
                + (statementRef == null ? "1 = 1" : "s.ref = :ref")
                + (openOnly ? " and i.status <> :matched and i.resolvedAt is null" : "")
                + " order by i.valueDate asc, i.id asc";
        var query = em.createQuery(jpql, ReconItem.class);
        if (statementRef != null) {
            query.setParameter("ref", statementRef);
        }
        if (openOnly) {
            query.setParameter("matched", ReconItem.MATCHED);
        }
        return query.setMaxResults(500).getResultStream().map(StatementReconciliation::itemView).toList();
    }

    @Transactional
    public CollectionsViews.ReconItemView assign(Long id, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_FINANCE");
        ReconItem item = open(id);
        String owner = text(body, "owner");
        if (owner == null || users.findByUsername(owner).isEmpty()) {
            throw new IllegalArgumentException("owner must be the username of a staff user.");
        }
        item.assign(owner);
        return itemView(item);
    }

    @Transactional
    public CollectionsViews.ReconItemView resolve(Long id, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_FINANCE");
        ReconItem item = open(id);
        String resolution = text(body, "resolution");
        if (resolution == null || resolution.length() < 10 || resolution.length() > 500) {
            throw new IllegalArgumentException("Say how this was resolved (10 to 500 characters).");
        }
        item.resolve(resolution, user.getUsername());
        return itemView(item);
    }

    private ReconItem open(Long id) {
        ReconItem item = em.find(ReconItem.class, id);
        if (item == null) {
            throw new NotFoundException("Reconciliation item " + id + " not found");
        }
        if (!item.isOpen()) {
            throw new ConflictException("Item " + id + " is " + (ReconItem.MATCHED.equals(item.getStatus())
                    ? "matched" : "already resolved") + "; nothing is open on it.");
        }
        return item;
    }

    private CollectionsViews.StatementView view(Statement s) {
        List<ReconItem> items = em.createQuery("select i from ReconItem i where i.statement = :s", ReconItem.class)
                .setParameter("s", s).getResultList();
        long lines = items.stream().filter(i -> STATEMENT.equals(i.getSide())).count();
        long matched = items.stream().filter(i -> ReconItem.MATCHED.equals(i.getStatus())).count();
        long open = items.stream().filter(ReconItem::isOpen).count();
        return new CollectionsViews.StatementView(s.getRef(), s.getProvider(), s.getPeriodFrom(), s.getPeriodTo(),
                s.getUploadedBy(), s.getUploadedAt(), lines, matched, open, totals(s, items));
    }

    private CollectionsViews.ControlTotals totals(Statement s, List<ReconItem> items) {
        BigDecimal statementTotal = items.stream().filter(i -> STATEMENT.equals(i.getSide()))
                .map(ReconItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal matchedTotal = items.stream().filter(i -> STATEMENT.equals(i.getSide())
                && ReconItem.MATCHED.equals(i.getStatus())).map(ReconItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        java.time.LocalDateTime from = s.getPeriodFrom().atStartOfDay();
        java.time.LocalDateTime to = s.getPeriodTo().plusDays(1).atStartOfDay();
        BigDecimal receipts = em.createQuery("select coalesce(sum(t.amount), 0) from PaymentTransaction t where"
                        + " lower(t.provider) = :provider and t.settledAt >= :from and t.settledAt < :to", BigDecimal.class)
                .setParameter("provider", s.getProvider().toLowerCase(Locale.ROOT)).setParameter("from", from)
                .setParameter("to", to).getSingleResult();
        receipts = receipts.add(em.createQuery("select coalesce(sum(m.amount), 0) from InboundMessage m where"
                        + " lower(m.provider) = :provider and m.receivedAt >= :from and m.receivedAt < :to", BigDecimal.class)
                .setParameter("provider", s.getProvider().toLowerCase(Locale.ROOT)).setParameter("from", from)
                .setParameter("to", to).getSingleResult());
        BigDecimal allocations = em.createQuery("select coalesce(sum(a.amount), 0) from Allocation a where"
                + " a.reversedAt is null and a.allocatedAt >= :from and a.allocatedAt < :to", BigDecimal.class)
                .setParameter("from", from).setParameter("to", to).getSingleResult();
        Object[] cash = em.createQuery("select coalesce(sum(l.debit), 0), coalesce(sum(l.credit), 0) from JournalLine l"
                        + " where l.accountCode = '1000' and l.journal.accountingDate >= :d1 and l.journal.accountingDate <= :d2",
                        Object[].class)
                .setParameter("d1", s.getPeriodFrom()).setParameter("d2", s.getPeriodTo()).getSingleResult();
        BigDecimal ledgerCash = ((BigDecimal) cash[0]).subtract((BigDecimal) cash[1]);
        return new CollectionsViews.ControlTotals(statementTotal, matchedTotal, receipts, allocations, ledgerCash,
                statementTotal.subtract(receipts));
    }

    static CollectionsViews.ReconItemView itemView(ReconItem i) {
        long age = i.isOpen() && i.getValueDate() != null
                ? Math.max(0, ChronoUnit.DAYS.between(i.getValueDate(), LocalDate.now())) : 0;
        return new CollectionsViews.ReconItemView(i.getId(), i.getStatement().getRef(), i.getSide(), i.getLineNo(),
                i.getProviderRef(), i.getAmount(), i.getCurrency(), i.getValueDate(), i.getStatus(),
                i.getMatchedType(), i.getMatchedRef(), i.getExpectedAmount(), i.getOwner(), age, i.getResolvedBy(),
                i.getResolvedAt(), i.getResolution());
    }

    private static String compare(BigDecimal amount, String currency, BigDecimal expected, String expectedCurrency) {
        if (expectedCurrency != null && !expectedCurrency.equalsIgnoreCase(currency)) {
            return ReconItem.CURRENCY_MISMATCH;
        }
        return expected != null && expected.compareTo(amount) == 0 ? ReconItem.MATCHED : ReconItem.AMOUNT_MISMATCH;
    }

    private static BigDecimal amount(JsonNode line, int lineNo) {
        String value = text(line, "amount");
        try {
            return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
        } catch (NullPointerException | NumberFormatException ex) {
            throw new IllegalArgumentException("Line " + lineNo + " needs an amount like 120.50.");
        }
    }

    private static LocalDate date(JsonNode node, String field, LocalDate fallback) {
        String value = text(node, field);
        if (value == null) {
            return fallback;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(field + " must be a date like 2026-11-01.");
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            return null;
        }
        return node.get(field).asText().trim();
    }
}
