package com.nsure.group;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.portfolio.GroupScheme;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheme billing (GL05): one invoice per scheme and month with a line per member on cover, pro-rated by days covered
 * for mid-month joiners and leavers (AT-GL05-01), and sponsor receipts allocated against it where a short payment
 * leaves an explicit outstanding balance (AT-GL05-02).
 */
@Service
public class SchemeBillingService {

    private static final Pattern PERIOD = Pattern.compile("\\d{4}-\\d{2}");

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final GroupRecords records;
    private final Outbox outbox;

    public SchemeBillingService(EntityManager em, PlatformSecurityContext security, GroupRecords records,
            Outbox outbox) {
        this.em = em;
        this.security = security;
        this.records = records;
        this.outbox = outbox;
    }

    /** Issues the invoice for a month, or returns the one already issued for that scheme and month. */
    @Transactional
    public GroupViews.InvoiceView invoice(String schemeRef, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("BILL_SCHEME");
        String periodText = GroupRecords.requiredText(body, "period", "Period", 7);
        YearMonth period;
        try {
            if (!PERIOD.matcher(periodText).matches()) {
                throw new DateTimeParseException("bad period", periodText, 0);
            }
            period = YearMonth.parse(periodText);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Period must be a month in the format yyyy-MM");
        }
        GroupScheme scheme = records.lockedScheme(schemeRef);
        com.nsure.common.ExpectedVersion.check("Scheme " + schemeRef, scheme.getRowVersion());
        SchemeInvoice existing = em.createQuery("select i from SchemeInvoice i where i.scheme = :scheme"
                        + " and i.period = :period", SchemeInvoice.class)
                .setParameter("scheme", scheme)
                .setParameter("period", period.toString())
                .getResultStream()
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return records.invoiceView(existing);
        }

        LocalDate start = period.atDay(1);
        LocalDate end = period.atEndOfMonth();
        int daysInPeriod = period.lengthOfMonth();
        List<SchemeMember> members = em.createQuery("select m from SchemeMember m join fetch m.category"
                        + " where m.scheme = :scheme and m.joinDate <= :end"
                        + " and (m.exitDate is null or m.exitDate >= :start)"
                        + " and m.status <> :declined order by m.memberKey, m.id", SchemeMember.class)
                .setParameter("scheme", scheme)
                .setParameter("start", start)
                .setParameter("end", end)
                .setParameter("declined", SchemeMember.DECLINED)
                .getResultList();

        SchemeInvoice invoice = new SchemeInvoice(records.nextRef(SchemeInvoice.class, "GIN-", 5), scheme,
                period.toString(), start, end, user.getUsername());
        // Scheme years run from the inception anniversary (renewal moves the renewal date on by a year).
        invoice.policyYear(Math.max(1, java.time.Period.between(scheme.getInceptionDate(), start).getYears() + 1));
        List<InvoiceLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (SchemeMember member : members) {
            if (member.getAcceptedCover().signum() <= 0) {
                continue;
            }
            LocalDate from = member.getJoinDate().isAfter(start) ? member.getJoinDate() : start;
            // The exit date is the member's last day of cover.
            LocalDate to = member.getExitDate() != null && member.getExitDate().isBefore(end)
                    ? member.getExitDate() : end;
            // Days the scheme was suspended are neither covered nor billed (NS-GL01-006).
            int days = (int) ChronoUnit.DAYS.between(from, to) + 1 - (to.isBefore(from) ? 0
                    : member.suspendedDays(from, to));
            if (days <= 0) {
                continue;
            }
            BigDecimal rate = member.ratePerMille(); // the category rate plus any underwriting loading (NS-GL03-004)
            // Pending cover is never billed: only the accepted (authorised) amount (AT-GL03-01).
            BigDecimal premium = member.getAcceptedCover()
                    .multiply(rate)
                    .multiply(BigDecimal.valueOf(days))
                    .divide(BigDecimal.valueOf(1000L * daysInPeriod), 2, RoundingMode.HALF_UP);
            lines.add(new InvoiceLine(invoice, member, rate, days, daysInPeriod, premium));
            total = total.add(premium);
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("No member of scheme " + schemeRef + " was on cover in " + period
                    + "; there is nothing to invoice");
        }
        invoice.totals(total, lines.size());
        em.persist(invoice);
        lines.forEach(em::persist);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemeRef", scheme.getRef());
        data.put("schemeName", scheme.getName());
        data.put("invoiceRef", invoice.getRef());
        data.put("period", invoice.getPeriod());
        data.put("policyYear", invoice.getPolicyYear());
        data.put("amount", total.toPlainString());
        outbox.publish(Events.SCHEME_INVOICE_ISSUED, invoice.getRef(), data, user.getUsername());
        return records.invoiceView(invoice);
    }

    /**
     * The arrears queue (GL05 workspace): every scheme invoice still owed after its billing period ended, oldest
     * first, with the days overdue and any amount the sponsor disputes.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> arrears() {
        security.authenticatedUser().validateHasPermissionTo("READ_SCHEME");
        LocalDate today = LocalDate.now();
        List<SchemeInvoice> owed = em.createQuery("select i from SchemeInvoice i join fetch i.scheme"
                        + " where i.outstanding > 0 and i.periodEnd < :today order by i.periodEnd, i.id", SchemeInvoice.class)
                .setParameter("today", today).getResultList();
        Map<String, GroupViews.InvoiceView> views = new java.util.HashMap<>();
        records.invoiceViews(owed).forEach(v -> views.put(v.ref(), v));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SchemeInvoice i : owed) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("invoiceRef", i.getRef());
            v.put("schemeRef", i.getScheme().getRef());
            v.put("schemeName", i.getScheme().getName());
            v.put("schemeStatus", i.getScheme().getStatus());
            v.put("period", i.getPeriod());
            v.put("policyYear", i.getPolicyYear());
            v.put("amount", i.getAmount());
            v.put("outstanding", i.getOutstanding());
            v.put("disputedAmount", views.get(i.getRef()).disputedAmount());
            v.put("status", i.getStatus());
            v.put("daysOverdue", ChronoUnit.DAYS.between(i.getPeriodEnd(), today));
            out.add(v);
        }
        return out;
    }

    /**
     * {memberKey, action RAISE|RESOLVE, note}: the sponsor disputes (or settles a dispute about) one member's line.
     * The dispute is tracked on the line and the invoice's disputed amount; it never changes the member's cover or the
     * receivable by itself (NS-GL05-003).
     */
    @Transactional
    public GroupViews.InvoiceView dispute(String invoiceRef, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("BILL_SCHEME");
        SchemeInvoice invoice = em.createQuery("select i from SchemeInvoice i join fetch i.scheme where i.ref = :ref",
                        SchemeInvoice.class)
                .setParameter("ref", invoiceRef)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Invoice " + invoiceRef + " not found"));
        String key = GroupRecords.requiredText(body, "memberKey", "Member key", 40);
        String action = GroupRecords.requiredText(body, "action", "Action", 10).toUpperCase(java.util.Locale.ROOT);
        String note = GroupRecords.requiredText(body, "note", "Note", 300);
        InvoiceLine line = em.createQuery("select l from InvoiceLine l where l.invoice = :i and l.memberKey = :k",
                        InvoiceLine.class)
                .setParameter("i", invoice).setParameter("k", key).getResultStream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invoice " + invoiceRef + " has no line for " + key));
        if ("RAISE".equals(action)) {
            if ("OPEN".equals(line.getDisputeStatus())) {
                throw new com.nsure.common.exception.ConflictException(key + " is already disputed on " + invoiceRef);
            }
            line.dispute("OPEN", note, user.getUsername());
        } else if ("RESOLVE".equals(action)) {
            if (!"OPEN".equals(line.getDisputeStatus())) {
                throw new IllegalArgumentException(key + " has no open dispute on " + invoiceRef);
            }
            line.dispute("RESOLVED", note, user.getUsername());
        } else {
            throw new IllegalArgumentException("Action must be RAISE or RESOLVE");
        }
        em.flush();
        return records.invoiceView(invoice);
    }

    /**
     * Allocates a sponsor payment to an invoice. A repeated payer reference returns the existing allocation; an
     * overpayment is refused; a short payment leaves the invoice Partially Paid with the balance outstanding.
     */
    @Transactional
    public GroupViews.ReceiptAllocationView receipt(String invoiceRef, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("BILL_SCHEME");
        SchemeInvoice invoice = em.createQuery("select i from SchemeInvoice i join fetch i.scheme where i.ref = :ref",
                        SchemeInvoice.class)
                .setParameter("ref", invoiceRef)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Invoice " + invoiceRef + " not found"));
        String reference = GroupRecords.requiredText(body, "reference", "Payment reference", 80);
        SponsorReceipt existing = em.createQuery("select r from SponsorReceipt r where r.invoice = :invoice"
                        + " and r.reference = :reference", SponsorReceipt.class)
                .setParameter("invoice", invoice)
                .setParameter("reference", reference)
                .getResultStream()
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return new GroupViews.ReceiptAllocationView(GroupRecords.receiptView(existing), records.invoiceView(invoice));
        }
        BigDecimal amount = GroupRecords.money(GroupRecords.decimal(body, "amount", "Amount"), "Amount", false);
        if (SchemeInvoice.PAID.equals(invoice.getStatus())) {
            throw new IllegalArgumentException("Invoice " + invoiceRef + " is already paid");
        }
        if (amount.compareTo(invoice.getOutstanding()) > 0) {
            throw new IllegalArgumentException("The receipt of " + amount.toPlainString()
                    + " is more than the outstanding balance of " + invoice.getOutstanding().toPlainString());
        }
        invoice.allocate(amount);
        SponsorReceipt receipt = new SponsorReceipt(records.nextRef(SponsorReceipt.class, "GRC-", 5), invoice,
                reference, amount, user.getUsername());
        em.persist(receipt);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemeRef", invoice.getScheme().getRef());
        data.put("invoiceRef", invoice.getRef());
        data.put("receiptRef", receipt.getRef());
        data.put("amount", amount.toPlainString());
        data.put("outstanding", invoice.getOutstanding().toPlainString());
        outbox.publish(Events.SPONSOR_RECEIPT_ALLOCATED, receipt.getRef(), data, user.getUsername());
        em.flush();
        return new GroupViews.ReceiptAllocationView(GroupRecords.receiptView(receipt), records.invoiceView(invoice));
    }
}
