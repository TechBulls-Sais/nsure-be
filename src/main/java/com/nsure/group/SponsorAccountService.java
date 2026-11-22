package com.nsure.group;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.portfolio.GroupScheme;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sponsor's account (GL05): lump-sum payments allocated by agreed rules with unallocated money held
 * (NS-GL05-003), billing exceptions for short payments, unallocated money and disputes (AT-GL05-02), and arrears with a
 * grace period, escalation and a second-person lapse decision that keeps member cover to the paid-to date
 * (NS-GL05-004).
 *
 * <p>Demo defaults (D02/D10, not approved rules): an invoice is due on the last day of its month; the grace period is
 * {@code nsure.group.billing.grace-days} (30) days after that; members of a lapsed scheme may apply to continue cover
 * individually for {@code nsure.group.billing.continuation-days} (31) days.
 */
@Service
public class SponsorAccountService {

    static final String OLDEST_FIRST = "OLDEST_FIRST";
    static final String INVOICES = "INVOICES";
    static final String MEMBERS = "MEMBERS";

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final GroupRecords records;
    private final SchemeLifecycle lifecycle;
    private final SupplementaryBilling supplementary;
    private final int graceDays;
    private final int continuationDays;

    public SponsorAccountService(EntityManager em, PlatformSecurityContext security, GroupRecords records,
            SchemeLifecycle lifecycle, SupplementaryBilling supplementary,
            @Value("${nsure.group.billing.grace-days:30}") int graceDays,
            @Value("${nsure.group.billing.continuation-days:31}") int continuationDays) {
        this.em = em;
        this.security = security;
        this.records = records;
        this.lifecycle = lifecycle;
        this.supplementary = supplementary;
        this.graceDays = graceDays;
        this.continuationDays = continuationDays;
    }

    // ── sponsor payments ───────────────────────────────────────────────────────────────────────────

    /**
     * {reference, amount, receivedDate?, rule OLDEST_FIRST|INVOICES|MEMBERS, invoices?[{invoiceRef, amount}],
     * members?[{memberKey, amount}], note?}: a lump-sum remittance. OLDEST_FIRST pays open invoices oldest first,
     * leaving disputed lines unpaid; INVOICES pays the named invoices; MEMBERS pays each member's open lines. What
     * cannot be allocated is held as unallocated money. A repeated payer reference returns the payment already taken.
     */
    @Transactional
    public Map<String, Object> pay(String schemeRef, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("BILL_SCHEME");
        GroupScheme scheme = records.lockedScheme(schemeRef);
        String reference = GroupRecords.requiredText(body, "reference", "Payment reference", 80);
        SponsorPayment existing = em.createQuery("select p from SponsorPayment p where p.scheme = :s"
                + " and p.reference = :r", SponsorPayment.class).setParameter("s", scheme).setParameter("r", reference)
                .getResultStream().findFirst().orElse(null);
        if (existing != null) {
            return paymentView(existing);
        }
        BigDecimal amount = GroupRecords.money(GroupRecords.decimal(body, "amount", "Amount"), "Amount", false);
        String rule = GroupRecords.text(body, "rule");
        rule = rule == null ? OLDEST_FIRST : rule.toUpperCase(Locale.ROOT);
        if (!List.of(OLDEST_FIRST, INVOICES, MEMBERS).contains(rule)) {
            throw new IllegalArgumentException("Allocation rule must be OLDEST_FIRST, INVOICES or MEMBERS");
        }
        String received = GroupRecords.text(body, "receivedDate");
        LocalDate receivedDate = received == null ? LocalDate.now() : GroupRecords.date(body, "receivedDate",
                "Received date");
        String note = GroupRecords.text(body, "note");
        SponsorPayment payment = new SponsorPayment(records.nextRef(SponsorPayment.class, "GSP-", 5), scheme,
                reference, SponsorPayment.CASH, rule, amount, receivedDate,
                note == null ? null : note.substring(0, Math.min(note.length(), 300)), user.getUsername());
        em.persist(payment);
        List<String> unmatched = new ArrayList<>();
        if (INVOICES.equals(rule)) {
            JsonNode list = body.path("invoices");
            if (!list.isArray() || list.isEmpty()) {
                throw new IllegalArgumentException("Name at least one invoice and amount for the INVOICES rule");
            }
            BigDecimal named = BigDecimal.ZERO;
            for (JsonNode n : list) {
                BigDecimal part = GroupRecords.money(GroupRecords.decimal(n, "amount", "Invoice amount"),
                        "Invoice amount", false);
                named = named.add(part);
                SchemeInvoice invoice = invoice(scheme, GroupRecords.requiredText(n, "invoiceRef", "Invoice", 20));
                BigDecimal applied = part.min(invoice.getOutstanding());
                if (applied.signum() > 0) {
                    allocate(payment, invoice, applied, null, user.getUsername(), false);
                }
                if (part.compareTo(applied) > 0) {
                    unmatched.add(invoice.getRef() + ": " + part.subtract(applied).toPlainString()
                            + " more than the balance");
                }
            }
            if (named.compareTo(amount) > 0) {
                throw new IllegalArgumentException("The named invoice amounts (" + named.toPlainString()
                        + ") are more than the payment of " + amount.toPlainString());
            }
        } else if (MEMBERS.equals(rule)) {
            allocateByMember(payment, scheme, body.path("members"), unmatched, user.getUsername());
        } else {
            allocateOldestFirst(payment, scheme, user.getUsername(), false);
        }
        if (payment.getUnallocated().signum() > 0) {
            // Unallocated money is held in suspense (UnmatchedReceipt), never applied to cover silently.
            if (unmatched.isEmpty()) {
                unmatched.add(payment.getUnallocated().toPlainString() + " not needed for open invoice balances"
                        + " (or held back by disputed lines)");
            }
            payment.unmatched(String.join("; ", unmatched));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("schemeRef", scheme.getRef());
            data.put("paymentRef", payment.getRef());
            data.put("reference", reference);
            data.put("amount", payment.getUnallocated().toPlainString());
            records.publish(Events.UNMATCHED_RECEIPT, payment.getRef(), data, user.getUsername());
            raise(scheme, BillingException.UNALLOCATED, null, payment.getRef(), payment.getUnallocated(),
                    "Sponsor payment " + reference + " has " + payment.getUnallocated().toPlainString()
                            + " unallocated: " + payment.getUnmatchedDetail(), user.getUsername());
        }
        em.flush();
        return paymentView(payment);
    }

    /** {invoiceRef, amount}: allocates held money (a payment's unallocated part, or a held credit) to an invoice. */
    @Transactional
    public Map<String, Object> allocateHeld(String paymentRef, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("BILL_SCHEME");
        SponsorPayment payment = em.createQuery("select p from SponsorPayment p join fetch p.scheme where p.ref = :r",
                        SponsorPayment.class).setParameter("r", paymentRef).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream().findFirst().orElseThrow(() -> new NotFoundException("Payment " + paymentRef
                        + " not found"));
        if (payment.getUnallocated().signum() <= 0) {
            throw new ConflictException(paymentRef + " has nothing left to allocate");
        }
        SchemeInvoice invoice = invoice(payment.getScheme(), GroupRecords.requiredText(body, "invoiceRef", "Invoice", 20));
        BigDecimal amount = GroupRecords.decimal(body, "amount", "Amount");
        amount = amount == null ? payment.getUnallocated().min(invoice.getOutstanding())
                : GroupRecords.money(amount, "Amount", false);
        if (amount.compareTo(payment.getUnallocated()) > 0) {
            throw new IllegalArgumentException("Only " + payment.getUnallocated().toPlainString() + " of " + paymentRef
                    + " is unallocated");
        }
        if (amount.signum() <= 0 || amount.compareTo(invoice.getOutstanding()) > 0) {
            throw new IllegalArgumentException("The amount must be more than zero and at most the balance of "
                    + invoice.getOutstanding().toPlainString() + " on " + invoice.getRef());
        }
        allocate(payment, invoice, amount, null, user.getUsername(), true);
        if (payment.getUnallocated().signum() <= 0) {
            resolveOpen(payment.getScheme(), BillingException.UNALLOCATED, null, payment.getRef(), user.getUsername(),
                    "Fully allocated");
        }
        em.flush();
        return paymentView(payment);
    }

    private void allocateOldestFirst(SponsorPayment payment, GroupScheme scheme, String actor, boolean fromHeld) {
        for (SchemeInvoice invoice : openInvoices(scheme)) {
            if (payment.getUnallocated().signum() <= 0) {
                break;
            }
            BigDecimal allocatable = invoice.getOutstanding().subtract(openDisputed(invoice)).max(BigDecimal.ZERO);
            BigDecimal part = payment.getUnallocated().min(allocatable);
            if (part.signum() > 0) {
                allocate(payment, invoice, part, null, actor, fromHeld);
            }
        }
    }

    private void allocateByMember(SponsorPayment payment, GroupScheme scheme, JsonNode rows, List<String> unmatched,
            String actor) {
        if (!rows.isArray() || rows.isEmpty()) {
            throw new IllegalArgumentException("List the member contributions (memberKey, amount) for the MEMBERS rule");
        }
        BigDecimal listed = BigDecimal.ZERO;
        List<SchemeInvoice> open = openInvoices(scheme);
        for (JsonNode row : rows) {
            String key = GroupRecords.requiredText(row, "memberKey", "Member key", 40);
            BigDecimal part = GroupRecords.money(GroupRecords.decimal(row, "amount", "Member amount"), "Member amount",
                    false);
            listed = listed.add(part);
            BigDecimal left = part;
            boolean known = false;
            for (SchemeInvoice invoice : open) {
                if (left.signum() <= 0) {
                    break;
                }
                InvoiceLine line = em.createQuery("select l from InvoiceLine l where l.invoice = :i"
                        + " and l.memberKey = :k", InvoiceLine.class).setParameter("i", invoice).setParameter("k", key)
                        .getResultStream().findFirst().orElse(null);
                if (line == null) {
                    continue;
                }
                known = true;
                BigDecimal lineOpen = line.getPremium().subtract(line.getPaidAmount()).max(BigDecimal.ZERO)
                        .min(invoice.getOutstanding());
                BigDecimal applied = left.min(lineOpen);
                if (applied.signum() > 0) {
                    allocate(payment, invoice, applied, line, actor, false);
                    left = left.subtract(applied);
                }
            }
            if (left.signum() > 0) {
                unmatched.add(key + ": " + left.toPlainString() + (known ? " more than the member's open lines"
                        : " unmatched (no open invoice line for this member)"));
            }
        }
        if (listed.compareTo(payment.getAmount()) != 0) {
            throw new IllegalArgumentException("The member contributions total " + listed.toPlainString()
                    + " but the payment is " + payment.getAmount().toPlainString());
        }
    }

    /**
     * One allocation: a sponsor receipt on the invoice (SponsorReceiptAllocated). Cash taken from suspense also clears
     * suspense first (SuspenseCleared) so the subledger moves it from suspense to the receivable; a held credit moves
     * nothing in the ledger because it was already posted when the credit was issued.
     */
    private void allocate(SponsorPayment payment, SchemeInvoice invoice, BigDecimal amount, InvoiceLine line,
            String actor, boolean fromHeld) {
        payment.allocate(amount);
        invoice.allocate(amount);
        if (line != null) {
            line.paid(amount);
        }
        long n = em.createQuery("select count(r) from SponsorReceipt r where r.paymentId = :p", Long.class)
                .setParameter("p", payment.getId()).getSingleResult();
        String reference = payment.getRef() + "/" + (n + 1) + " " + payment.getReference();
        SponsorReceipt receipt = new SponsorReceipt(records.nextRef(SponsorReceipt.class, "GRC-", 5), invoice,
                reference.length() <= 80 ? reference : reference.substring(0, 80), amount, actor, payment.getId());
        em.persist(receipt);
        em.flush();
        if (SponsorPayment.CASH.equals(payment.getKind())) {
            if (fromHeld) {
                Map<String, Object> cleared = new LinkedHashMap<>();
                cleared.put("schemeRef", invoice.getScheme().getRef());
                cleared.put("paymentRef", payment.getRef());
                cleared.put("invoiceRef", invoice.getRef());
                cleared.put("amount", amount.toPlainString());
                records.publish(Events.SUSPENSE_CLEARED, payment.getRef(), cleared, actor);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("schemeRef", invoice.getScheme().getRef());
            data.put("invoiceRef", invoice.getRef());
            data.put("receiptRef", receipt.getRef());
            data.put("paymentRef", payment.getRef());
            data.put("amount", amount.toPlainString());
            data.put("outstanding", invoice.getOutstanding().toPlainString());
            records.publish(Events.SPONSOR_RECEIPT_ALLOCATED, receipt.getRef(), data, actor);
        }
        afterAllocation(invoice, actor);
    }

    /** A short payment raises (or updates) an exception; a paid invoice resolves it (AT-GL05-02). */
    void afterAllocation(SchemeInvoice invoice, String actor) {
        if (SchemeInvoice.PAID.equals(invoice.getStatus())) {
            resolveOpen(invoice.getScheme(), BillingException.SHORT_PAYMENT, invoice.getRef(), null, actor,
                    "Invoice paid in full");
        } else if (invoice.getPaidAmount().signum() > 0) {
            String detail = "Invoice " + invoice.getRef() + " (" + invoice.getPeriod() + ") short paid: "
                    + invoice.getOutstanding().toPlainString() + " still outstanding; members' cover is unchanged";
            BillingException open = openException(invoice.getScheme(), BillingException.SHORT_PAYMENT, invoice.getRef(),
                    null);
            if (open == null) {
                raise(invoice.getScheme(), BillingException.SHORT_PAYMENT, invoice.getRef(), null,
                        invoice.getOutstanding(), detail, actor);
            } else {
                open.update(invoice.getOutstanding(), detail);
            }
        }
    }

    /** A disputed line raises an exception; resolving the dispute resolves it. */
    void disputeChanged(SchemeInvoice invoice, InvoiceLine line, String actor) {
        if ("OPEN".equals(line.getDisputeStatus())) {
            raise(invoice.getScheme(), BillingException.DISPUTE, invoice.getRef(), null, line.getPremium(),
                    "Sponsor disputes " + line.getMemberKey() + " on " + invoice.getRef() + ": " + line.getDisputeNote(),
                    actor);
        } else {
            for (BillingException e : em.createQuery("select e from BillingException e where e.scheme = :s"
                            + " and e.type = :t and e.invoiceRef = :i and e.status = :o and e.detail like :k",
                            BillingException.class)
                    .setParameter("s", invoice.getScheme()).setParameter("t", BillingException.DISPUTE)
                    .setParameter("i", invoice.getRef()).setParameter("o", BillingException.OPEN)
                    .setParameter("k", "Sponsor disputes " + line.getMemberKey() + " %").getResultList()) {
                e.resolve(actor, "Dispute resolved: " + line.getDisputeNote());
            }
        }
    }

    // ── exceptions ─────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> exceptions(String status) {
        security.authenticatedUser().validateHasPermissionTo("READ_SCHEME");
        String wanted = status == null || status.isBlank() ? BillingException.OPEN : status.toUpperCase(Locale.ROOT);
        return em.createQuery("select e from BillingException e join fetch e.scheme where e.status = :s"
                        + " order by e.raisedAt, e.id", BillingException.class)
                .setParameter("s", wanted).getResultList().stream().map(SponsorAccountService::exceptionView).toList();
    }

    /** {note}: resolves an exception by hand. */
    @Transactional
    public Map<String, Object> resolveException(Long id, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("BILL_SCHEME");
        BillingException e = em.find(BillingException.class, id);
        if (e == null) {
            throw new NotFoundException("Exception " + id + " not found");
        }
        if (!BillingException.OPEN.equals(e.getStatus())) {
            throw new ConflictException("Exception " + id + " is already resolved");
        }
        e.resolve(user.getUsername(), GroupRecords.requiredText(body, "note", "Resolution note", 300));
        return exceptionView(e);
    }

    private void raise(GroupScheme scheme, String type, String invoiceRef, String paymentRef, BigDecimal amount,
            String detail, String actor) {
        em.persist(new BillingException(scheme, type, invoiceRef, paymentRef, amount, detail, actor));
    }

    private BillingException openException(GroupScheme scheme, String type, String invoiceRef, String paymentRef) {
        String jpql = "select e from BillingException e where e.scheme = :s and e.type = :t and e.status = :o"
                + (invoiceRef == null ? " and e.invoiceRef is null" : " and e.invoiceRef = :i")
                + (paymentRef == null ? " and e.paymentRef is null" : " and e.paymentRef = :p");
        var query = em.createQuery(jpql, BillingException.class).setParameter("s", scheme).setParameter("t", type)
                .setParameter("o", BillingException.OPEN);
        if (invoiceRef != null) {
            query.setParameter("i", invoiceRef);
        }
        if (paymentRef != null) {
            query.setParameter("p", paymentRef);
        }
        return query.setMaxResults(1).getResultStream().findFirst().orElse(null);
    }

    private void resolveOpen(GroupScheme scheme, String type, String invoiceRef, String paymentRef, String actor,
            String note) {
        BillingException open = openException(scheme, type, invoiceRef, paymentRef);
        if (open != null) {
            open.resolve(actor, note);
        }
    }

    // ── arrears, grace and lapse ───────────────────────────────────────────────────────────────────

    /** The arrears position of one invoice: due date, grace end and stage (DUE, IN_GRACE, GRACE_EXPIRED). */
    Map<String, Object> arrearsStage(SchemeInvoice invoice, LocalDate today) {
        LocalDate due = invoice.getPeriodEnd();
        LocalDate graceEnds = due.plusDays(graceDays);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("dueDate", due);
        v.put("graceEnds", graceEnds);
        v.put("stage", !today.isAfter(due) ? "DUE" : !today.isAfter(graceEnds) ? "IN_GRACE" : "GRACE_EXPIRED");
        v.put("graceDays", graceDays);
        return v;
    }

    /** What must be resolved before adverse cover action: open disputes and unallocated sponsor money. */
    List<String> escalationBlockers(GroupScheme scheme) {
        List<String> blockers = new ArrayList<>();
        Long disputes = em.createQuery("select count(l) from InvoiceLine l where l.invoice.scheme = :s"
                + " and l.disputeStatus = 'OPEN' and l.invoice.outstanding > 0", Long.class)
                .setParameter("s", scheme).getSingleResult();
        if (disputes > 0) {
            blockers.add(disputes + " disputed line(s) on unpaid invoices");
        }
        BigDecimal held = em.createQuery("select coalesce(sum(p.unallocated), 0) from SponsorPayment p"
                + " where p.scheme = :s and p.unallocated > 0", BigDecimal.class).setParameter("s", scheme)
                .getSingleResult();
        if (held.signum() > 0) {
            blockers.add(held.setScale(2).toPlainString() + " of sponsor money or credit is unallocated");
        }
        return blockers;
    }

    /**
     * {note}: proposes cancelling the scheme for non-payment once the grace period after its oldest unpaid invoice has
     * run out. Open disputes or unallocated money escalate instead (an exception for finance) and nothing is proposed.
     */
    @Transactional
    public Map<String, Object> proposeLapse(String schemeRef, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("BILL_SCHEME");
        GroupScheme scheme = records.lockedScheme(schemeRef);
        if (!"ACTIVE".equals(scheme.getStatus())) {
            throw new IllegalArgumentException(schemeRef + " is " + scheme.getStatus().toLowerCase(Locale.ROOT)
                    + "; only an active scheme can lapse");
        }
        String note = GroupRecords.requiredText(body, "note", "Note", 500);
        if (em.createQuery("select count(l) from SchemeLapse l where l.scheme = :s and l.status = :p", Long.class)
                .setParameter("s", scheme).setParameter("p", SchemeLapse.PROPOSED).getSingleResult() > 0) {
            throw new ConflictException(schemeRef + " already has a lapse awaiting a decision");
        }
        LocalDate today = LocalDate.now();
        List<SchemeInvoice> open = openInvoices(scheme);
        SchemeInvoice oldest = open.isEmpty() ? null : open.get(0);
        if (oldest == null || !"GRACE_EXPIRED".equals(arrearsStage(oldest, today).get("stage"))) {
            throw new IllegalArgumentException(schemeRef + " has no invoice unpaid beyond the " + graceDays
                    + "-day grace period" + (oldest == null ? "" : " (oldest unpaid " + oldest.getRef() + ", grace ends "
                    + arrearsStage(oldest, today).get("graceEnds") + ")"));
        }
        List<String> blockers = escalationBlockers(scheme);
        Map<String, Object> out = new LinkedHashMap<>();
        if (!blockers.isEmpty()) {
            String detail = "Lapse of " + schemeRef + " escalated before any cover action: " + String.join("; ", blockers)
                    + ". Resolve them, then propose the lapse again.";
            if (openException(scheme, BillingException.LAPSE_ESCALATED, oldest.getRef(), null) == null) {
                raise(scheme, BillingException.LAPSE_ESCALATED, oldest.getRef(), null, arrears(open), detail,
                        user.getUsername());
            }
            out.put("outcome", "ESCALATED");
            out.put("blockers", blockers);
            out.put("detail", detail);
            return out;
        }
        LocalDate paidTo = paidTo(scheme);
        LocalDate effective = paidTo == null ? scheme.getInceptionDate() : paidTo.plusDays(1);
        int members = records.membersOnCover(scheme).size();
        SchemeLapse lapse = new SchemeLapse(records.nextRef(SchemeLapse.class, "GLP-", 5), scheme, paidTo, effective,
                (LocalDate) arrearsStage(oldest, today).get("graceEnds"), arrears(open), oldest.getRef(),
                today.plusDays(continuationDays), members, note, user.getUsername());
        em.persist(lapse);
        out.put("outcome", "PROPOSED");
        out.put("lapse", lapseView(lapse));
        return out;
    }

    /**
     * {approve, note}: the lapse decision, by someone other than the proposer. A scheme that has paid since is cured.
     * On approval members keep cover to the paid-to date, invoiced months after it are credited, and the scheme closes.
     */
    @Transactional
    public Map<String, Object> decideLapse(String ref, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("UNDERWRITE_MEMBER");
        SchemeLapse lapse = em.createQuery("select l from SchemeLapse l join fetch l.scheme where l.ref = :r",
                SchemeLapse.class).setParameter("r", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Lapse " + ref + " not found"));
        if (!SchemeLapse.PROPOSED.equals(lapse.getStatus())) {
            throw new ConflictException(ref + " is already " + lapse.getStatus().toLowerCase(Locale.ROOT));
        }
        if (user.getUsername().equalsIgnoreCase(lapse.getProposedBy())) {
            throw new NoAuthorizationException("You proposed " + ref + ", so someone else must decide it.");
        }
        String note = GroupRecords.requiredText(body, "note", "Decision note", 300);
        GroupScheme scheme = records.lockedScheme(lapse.getScheme().getRef());
        if (!body.path("approve").asBoolean(false)) {
            lapse.decide(SchemeLapse.REJECTED, user.getUsername(), note);
            return lapseView(lapse);
        }
        List<SchemeInvoice> open = openInvoices(scheme);
        if (open.isEmpty() || !"GRACE_EXPIRED".equals(arrearsStage(open.get(0), LocalDate.now()).get("stage"))) {
            lapse.decide(SchemeLapse.CURED, user.getUsername(), "Arrears paid before the decision. " + note);
            return lapseView(lapse);
        }
        if (!escalationBlockers(scheme).isEmpty()) {
            throw new IllegalArgumentException("Disputes or unallocated money appeared since the proposal: "
                    + String.join("; ", escalationBlockers(scheme)) + ". Resolve them first.");
        }
        List<SchemeMember> members = new ArrayList<>(records.membersOnCover(scheme).values());
        lifecycle.close(scheme, lapse.getEffectiveDate(), user.getUsername(), ref);
        scheme.setStatus("CLOSED");
        lapse.decide(SchemeLapse.APPROVED, user.getUsername(), note);
        // Cover after the paid-to date is not charged: invoiced months after it are credited (NS-GL05-002).
        Map<Long, LocalDate> changed = new HashMap<>();
        members.forEach(m -> changed.put(m.getId(), lapse.getEffectiveDate()));
        supplementary.reconcile(scheme, changed, ref, user.getUsername());
        records.refreshTotals(scheme);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemeRef", scheme.getRef());
        data.put("closedOn", lapse.getEffectiveDate().toString());
        data.put("reason", "Lapsed for non-payment (" + ref + ")");
        data.put("paidToDate", lapse.getPaidToDate() == null ? null : lapse.getPaidToDate().toString());
        data.put("membersCeased", members.size());
        data.put("continuationUntil", lapse.getContinuationUntil().toString());
        records.publish(Events.SCHEME_CLOSED, scheme.getRef(), data, user.getUsername());
        em.flush();
        return lapseView(lapse);
    }

    /** The end of the last month the sponsor has paid in full, counting from the first invoice; null if none. */
    LocalDate paidTo(GroupScheme scheme) {
        LocalDate paidTo = null;
        for (SchemeInvoice i : em.createQuery("select i from SchemeInvoice i where i.scheme = :s order by i.periodStart",
                SchemeInvoice.class).setParameter("s", scheme).getResultList()) {
            if (i.getOutstanding().signum() > 0) {
                return paidTo == null ? i.getPeriodStart().minusDays(1) : paidTo;
            }
            paidTo = i.getPeriodEnd();
        }
        return paidTo;
    }

    // ── account view ───────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> account(String schemeRef) {
        security.authenticatedUser().validateHasPermissionTo("READ_SCHEME");
        GroupScheme scheme = records.scheme(schemeRef);
        LocalDate today = LocalDate.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemeRef", schemeRef);
        out.put("graceDays", graceDays);
        out.put("paidToDate", paidTo(scheme));
        List<SchemeInvoice> open = openInvoices(scheme);
        out.put("arrears", arrears(open));
        List<Map<String, Object>> invoices = new ArrayList<>();
        for (SchemeInvoice i : open) {
            Map<String, Object> v = new LinkedHashMap<>(arrearsStage(i, today));
            v.put("invoiceRef", i.getRef());
            v.put("period", i.getPeriod());
            v.put("outstanding", i.getOutstanding());
            v.put("disputed", openDisputed(i));
            invoices.add(v);
        }
        out.put("openInvoices", invoices);
        out.put("stage", invoices.isEmpty() ? "UP_TO_DATE" : invoices.get(0).get("stage"));
        out.put("blockers", escalationBlockers(scheme));
        out.put("payments", em.createQuery("select p from SponsorPayment p where p.scheme = :s order by p.id desc",
                SponsorPayment.class).setParameter("s", scheme).getResultList().stream().map(this::paymentView).toList());
        out.put("exceptions", em.createQuery("select e from BillingException e join fetch e.scheme where e.scheme = :s"
                        + " order by e.id desc", BillingException.class).setParameter("s", scheme).getResultList()
                .stream().map(SponsorAccountService::exceptionView).toList());
        out.put("lapses", em.createQuery("select l from SchemeLapse l join fetch l.scheme where l.scheme = :s"
                + " order by l.id desc", SchemeLapse.class).setParameter("s", scheme).getResultList().stream()
                .map(SponsorAccountService::lapseView).toList());
        return out;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    private List<SchemeInvoice> openInvoices(GroupScheme scheme) {
        return em.createQuery("select i from SchemeInvoice i where i.scheme = :s and i.outstanding > 0"
                + " order by i.periodStart, i.id", SchemeInvoice.class).setParameter("s", scheme).getResultList();
    }

    private SchemeInvoice invoice(GroupScheme scheme, String ref) {
        SchemeInvoice invoice = em.createQuery("select i from SchemeInvoice i where i.ref = :r", SchemeInvoice.class)
                .setParameter("r", ref).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Invoice " + ref + " not found"));
        if (!invoice.getScheme().getId().equals(scheme.getId())) {
            throw new IllegalArgumentException("Invoice " + ref + " belongs to another scheme");
        }
        return invoice;
    }

    private BigDecimal openDisputed(SchemeInvoice invoice) {
        return em.createQuery("select coalesce(sum(l.premium - l.paidAmount), 0) from InvoiceLine l"
                + " where l.invoice = :i and l.disputeStatus = 'OPEN'", BigDecimal.class).setParameter("i", invoice)
                .getSingleResult().max(BigDecimal.ZERO).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal arrears(List<SchemeInvoice> open) {
        return open.stream().map(SchemeInvoice::getOutstanding).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    Map<String, Object> paymentView(SponsorPayment p) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ref", p.getRef());
        v.put("reference", p.getReference());
        v.put("kind", p.getKind());
        v.put("rule", p.getRule());
        v.put("amount", p.getAmount());
        v.put("allocated", p.getAllocated());
        v.put("unallocated", p.getUnallocated());
        v.put("status", p.getStatus());
        v.put("receivedDate", p.getReceivedDate());
        v.put("unmatched", p.getUnmatchedDetail());
        v.put("note", p.getNote());
        v.put("receivedBy", p.getReceivedBy());
        v.put("receivedAt", p.getReceivedAt());
        v.put("allocations", em.createQuery("select r from SponsorReceipt r join fetch r.invoice where r.paymentId = :p"
                + " order by r.id", SponsorReceipt.class).setParameter("p", p.getId()).getResultList().stream()
                .map(r -> {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("receiptRef", r.getRef());
                    a.put("invoiceRef", r.getInvoice().getRef());
                    a.put("period", r.getInvoice().getPeriod());
                    a.put("amount", r.getAmount());
                    a.put("outstandingAfter", r.getOutstandingAfter());
                    return a;
                }).toList());
        return v;
    }

    static Map<String, Object> exceptionView(BillingException e) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", e.getId());
        v.put("schemeRef", e.getScheme().getRef());
        v.put("schemeName", e.getScheme().getName());
        v.put("type", e.getType());
        v.put("invoiceRef", e.getInvoiceRef());
        v.put("paymentRef", e.getPaymentRef());
        v.put("amount", e.getAmount());
        v.put("detail", e.getDetail());
        v.put("status", e.getStatus());
        v.put("raisedBy", e.getRaisedBy());
        v.put("raisedAt", e.getRaisedAt());
        v.put("ageDays", ChronoUnit.DAYS.between(e.getRaisedAt().toLocalDate(), LocalDate.now()));
        v.put("resolvedBy", e.getResolvedBy());
        v.put("resolvedAt", e.getResolvedAt());
        v.put("resolutionNote", e.getResolutionNote());
        return v;
    }

    static Map<String, Object> lapseView(SchemeLapse l) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ref", l.getRef());
        v.put("schemeRef", l.getScheme().getRef());
        v.put("status", l.getStatus());
        v.put("paidToDate", l.getPaidToDate());
        v.put("effectiveDate", l.getEffectiveDate());
        v.put("graceEnded", l.getGraceEnded());
        v.put("arrearsAmount", l.getArrearsAmount());
        v.put("oldestInvoiceRef", l.getOldestInvoiceRef());
        v.put("continuationUntil", l.getContinuationUntil());
        v.put("membersAffected", l.getMembersAffected());
        v.put("note", l.getNote());
        v.put("proposedBy", l.getProposedBy());
        v.put("proposedAt", l.getProposedAt());
        v.put("decidedBy", l.getDecidedBy());
        v.put("decidedAt", l.getDecidedAt());
        v.put("decisionNote", l.getDecisionNote());
        v.put("memberContinuity", "Members keep cover to " + (l.getPaidToDate() == null ? "the inception date"
                : l.getPaidToDate()) + "; claims with an event on or before that date stay payable; members may apply"
                + " to continue cover individually until " + l.getContinuationUntil() + " (demo rule).");
        return v;
    }
}
