package com.nsure.group;

import com.nsure.integration.Events;
import com.nsure.portfolio.GroupScheme;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Supplementary billing (NS-GL05-002). After member changes, each invoice already issued for a month the changes reach
 * is re-priced from the members' cover periods; the difference to what was billed (the original line plus earlier
 * adjustments) becomes a debit or credit adjustment that references the original lines. Issued invoices are never
 * edited: the adjustment changes the invoice's balance, and a credit beyond that balance is held for the sponsor. Each
 * adjustment is published as SchemeInvoiceIssued (invoiceType SUPPLEMENTARY) so the subledger posts it (a credit posts
 * the other way round). Runs in the caller's transaction.
 */
@Component
public class SupplementaryBilling {

    private final EntityManager em;
    private final CoverHistory cover;
    private final GroupRecords records;

    public SupplementaryBilling(EntityManager em, CoverHistory cover, GroupRecords records) {
        this.em = em;
        this.cover = cover;
        this.records = records;
    }

    /** Before an underwriting decision changes the member: the cover in force so far is on record. */
    void beforeUnderwriting(SchemeMember member) {
        cover.baseline(member);
    }

    /**
     * After an underwriting decision: the decided cover replaces the terms of the cover period that requested it (the
     * cover is authorised from the date it was asked for), and months already invoiced are re-priced.
     */
    void afterUnderwriting(SchemeMember member, Long changeId, String actor) {
        LocalDate from = cover.decided(member, changeId);
        if (from != null) {
            reconcile(member.getScheme(), Map.of(member.getId(), from), "Underwriting " + member.getMemberKey(), actor);
        }
    }

    /**
     * Re-prices invoiced months for the members changed from the given dates. Returns the adjustment references created
     * for each member.
     */
    Map<Long, List<String>> reconcile(GroupScheme scheme, Map<Long, LocalDate> changedFrom, String sourceRef,
            String actor) {
        Map<Long, List<String>> out = new HashMap<>();
        if (changedFrom.isEmpty()) {
            return out;
        }
        em.flush();
        LocalDate earliest = changedFrom.values().stream().min(LocalDate::compareTo).orElseThrow();
        List<SchemeInvoice> invoices = em.createQuery("select i from SchemeInvoice i where i.scheme = :s"
                        + " and i.periodEnd >= :from order by i.periodStart, i.id", SchemeInvoice.class)
                .setParameter("s", scheme).setParameter("from", earliest).getResultList();
        if (invoices.isEmpty()) {
            return out;
        }
        List<SchemeMember> members = em.createQuery("select m from SchemeMember m join fetch m.category"
                + " where m.id in :ids", SchemeMember.class).setParameter("ids", changedFrom.keySet()).getResultList();
        Map<Long, List<MemberCoverPeriod>> periods = cover.periods(members);
        Map<Long, List<CategoryVersion>> versions = cover.versions(records.categories(scheme));
        Map<Long, String> lastChange = new HashMap<>();
        for (MemberChange c : em.createQuery("select c from MemberChange c where c.member.id in :ids order by c.id",
                MemberChange.class).setParameter("ids", changedFrom.keySet()).getResultList()) {
            lastChange.put(c.getMember().getId(), c.getChangeType());
        }
        for (SchemeInvoice invoice : invoices) {
            int daysInPeriod = CoverHistory.days(invoice.getPeriodStart(), invoice.getPeriodEnd());
            Map<Long, InvoiceLine> lines = new HashMap<>();
            em.createQuery("select l from InvoiceLine l where l.invoice = :i", InvoiceLine.class)
                    .setParameter("i", invoice).getResultList().forEach(l -> lines.put(l.getMemberId(), l));
            Map<Long, BigDecimal> adjusted = new HashMap<>();
            for (Object[] row : em.createQuery("select l.memberId, sum(l.amount) from AdjustmentLine l"
                    + " where l.adjustment.invoice = :i group by l.memberId", Object[].class)
                    .setParameter("i", invoice).getResultList()) {
                adjusted.put((Long) row[0], (BigDecimal) row[1]);
            }
            List<Object[]> pending = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            for (SchemeMember m : members) {
                if (changedFrom.get(m.getId()).isAfter(invoice.getPeriodEnd())) {
                    continue;
                }
                CoverHistory.Priced priced = cover.price(m, periods.get(m.getId()), versions,
                        invoice.getPeriodStart(), invoice.getPeriodEnd(), daysInPeriod);
                InvoiceLine original = lines.get(m.getId());
                BigDecimal billed = (original == null ? BigDecimal.ZERO.setScale(2) : original.getPremium())
                        .add(adjusted.getOrDefault(m.getId(), BigDecimal.ZERO.setScale(2)));
                BigDecimal delta = priced.premium().subtract(billed);
                if (delta.abs().compareTo(new BigDecimal("0.01")) < 0) {
                    continue;
                }
                pending.add(new Object[] {m, original, priced, billed});
                total = total.add(delta);
            }
            if (pending.isEmpty()) {
                continue;
            }
            SchemeAdjustment adjustment = new SchemeAdjustment(records.nextRef(SchemeAdjustment.class, "GAD-", 5),
                    invoice, total, pending.size(), sourceRef, reason(sourceRef, invoice), actor);
            em.persist(adjustment);
            for (Object[] p : pending) {
                SchemeMember m = (SchemeMember) p[0];
                InvoiceLine original = (InvoiceLine) p[1];
                em.persist(new AdjustmentLine(adjustment, original == null ? null : original.getId(), m,
                        lastChange.get(m.getId()), (CoverHistory.Priced) p[2], (BigDecimal) p[3]));
                out.computeIfAbsent(m.getId(), k -> new ArrayList<>()).add(adjustment.getRef());
            }
            BigDecimal applied = invoice.adjust(total);
            BigDecimal held = total.signum() < 0 ? total.negate().subtract(applied.negate()) : BigDecimal.ZERO.setScale(2);
            adjustment.settled(applied.abs(), held);
            if (held.signum() > 0) {
                // A credit beyond the invoice's balance is held for the sponsor, to allocate to another invoice.
                SponsorPayment credit = new SponsorPayment(records.nextRef(SponsorPayment.class, "GSP-", 5), scheme,
                        adjustment.getRef(), SponsorPayment.CREDIT_NOTE, "HELD", held, LocalDate.now(),
                        "Credit from " + adjustment.getRef() + " beyond the balance of " + invoice.getRef(), actor);
                em.persist(credit);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("schemeRef", scheme.getRef());
            data.put("schemeName", scheme.getName());
            data.put("invoiceRef", adjustment.getRef());
            data.put("invoiceType", "SUPPLEMENTARY");
            data.put("originalInvoiceRef", invoice.getRef());
            data.put("period", invoice.getPeriod());
            data.put("policyYear", invoice.getPolicyYear());
            data.put("adjustmentType", adjustment.getType());
            data.put("lines", pending.size());
            data.put("sourceRef", sourceRef);
            data.put("amount", total.toPlainString());
            records.publish(Events.SCHEME_INVOICE_ISSUED, adjustment.getRef(), data, actor);
        }
        em.flush();
        return out;
    }

    private static String reason(String sourceRef, SchemeInvoice invoice) {
        String source = sourceRef == null ? "member changes" : sourceRef.startsWith("CEN-") ? "census " + sourceRef
                : sourceRef;
        return "Backdated " + source + " re-priced " + invoice.getPeriod() + " (" + invoice.getRef() + ")";
    }
}
