package com.nsure.finance;

import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * NS-SH08-004 aged receivables and payables from the operational records the subledger posts from: individual
 * instalments and group invoices still owed; commission statements, refunds and approved claims not yet paid; and
 * reinsurance recoveries still expected. Ages run from the due (or approval) date in 0-30, 31-60, 61-90 and 90+ days.
 */
@Service
public class FinanceReports {

    static final List<String> BUCKETS = List.of("0-30", "31-60", "61-90", "90+");

    private final EntityManager em;
    private final PlatformSecurityContext security;

    public FinanceReports(EntityManager em, PlatformSecurityContext security) {
        this.em = em;
        this.security = security;
    }

    @Transactional(readOnly = true)
    public FinanceViews.AgeingView ageing() {
        security.authenticatedUser().validateHasPermissionTo("READ_FINANCE");
        LocalDate today = LocalDate.now();
        List<FinanceViews.AgedItem> receivables = new ArrayList<>();
        for (Object[] r : rows("select i.record_ref, i.policy_ref, i.due_date, i.billed_amount"
                + " + coalesce((select sum(a.amount) from bil_adjustment a where a.instalment_id = i.id), 0)"
                + " - coalesce((select sum(l.amount) from bil_allocation l where l.instalment_id = i.id"
                + " and l.reversed_at is null), 0) from bil_instalment i where i.status in ('DUE', 'PART_PAID')"
                + " and i.due_date <= ?", today)) {
            add(receivables, "Individual instalment", r[0], r[1], date(r[2]), money(r[3]), today);
        }
        for (Object[] r : rows("select i.record_ref, s.name, i.period_start, i.outstanding from grp_invoice i join"
                + " ins_scheme s on s.id = i.scheme_id where i.outstanding > 0 and i.period_start <= ?", today)) {
            add(receivables, "Group invoice", r[0], r[1], date(r[2]), money(r[3]), today);
        }
        for (Object[] r : rows("select r.record_ref, t.reinsurer, r.created_at, r.recovery_amount from ri_recovery r"
                + " join ri_treaty t on t.id = r.treaty_id where r.status = 'EXPECTED' and r.created_at <= ?",
                today.plusDays(1).atStartOfDay())) {
            add(receivables, "Reinsurance recovery", r[0], r[1], date(r[2]), money(r[3]), today);
        }
        List<FinanceViews.AgedItem> payables = new ArrayList<>();
        for (Object[] r : rows("select s.record_ref, i.name, s.issued_at, s.net_amount from dist_statement s join"
                + " dist_intermediary i on i.id = s.intermediary_id where s.status in ('ISSUED', 'APPROVED')"
                + " and s.net_amount > 0 and s.issued_at <= ?", today.plusDays(1).atStartOfDay())) {
            add(payables, "Commission statement", r[0], r[1], date(r[2]), money(r[3]), today);
        }
        for (Object[] r : rows("select r.record_ref, r.payee_name, r.requested_at, r.amount from fin_refund r"
                + " where r.status in ('PENDING_APPROVAL', 'PAYOUT_SENT') and r.requested_at <= ?",
                today.plusDays(1).atStartOfDay())) {
            add(payables, "Refund", r[0], r[1], date(r[2]), money(r[3]), today);
        }
        for (Object[] r : rows("select c.record_ref, c.customer_name, c.resolved_at, c.payout_amount from ins_claim c"
                + " where c.status = 'Approved' and c.payout_amount is not null and c.resolved_at <= ?",
                today.plusDays(1).atStartOfDay())) {
            add(payables, "Approved claim", r[0], r[1], date(r[2]), money(r[3]), today);
        }
        return new FinanceViews.AgeingView(today, BUCKETS, receivables, totals(receivables), payables, totals(payables));
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql, Object param) {
        return em.createNativeQuery(sql).setParameter(1, param).getResultList();
    }

    private static void add(List<FinanceViews.AgedItem> into, String kind, Object ref, Object party, LocalDate due,
            BigDecimal amount, LocalDate today) {
        if (amount == null || amount.signum() <= 0 || due == null) {
            return;
        }
        long age = Math.max(0, ChronoUnit.DAYS.between(due, today));
        String bucket = age <= 30 ? BUCKETS.get(0) : age <= 60 ? BUCKETS.get(1) : age <= 90 ? BUCKETS.get(2) : BUCKETS.get(3);
        into.add(new FinanceViews.AgedItem(kind, String.valueOf(ref), party == null ? null : String.valueOf(party), due,
                amount.setScale(2, java.math.RoundingMode.HALF_UP), age, bucket));
    }

    private static Map<String, BigDecimal> totals(List<FinanceViews.AgedItem> items) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        BUCKETS.forEach(b -> totals.put(b, BigDecimal.ZERO.setScale(2)));
        items.forEach(i -> totals.merge(i.bucket(), i.amount(), BigDecimal::add));
        totals.put("TOTAL", items.stream().map(FinanceViews.AgedItem::amount).reduce(BigDecimal.ZERO.setScale(2),
                BigDecimal::add));
        return totals;
    }

    private static BigDecimal money(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }

    private static LocalDate date(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (value instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime().toLocalDate();
        }
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof LocalDateTime t) {
            return t.toLocalDate();
        }
        return LocalDate.parse(value.toString().substring(0, 10));
    }
}
