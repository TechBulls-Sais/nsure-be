package com.nsure.reporting;

import static com.nsure.reporting.ReportSql.amountOf;
import static com.nsure.reporting.ReportSql.countOf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.finance.FinanceService;
import com.nsure.finance.FinanceViews;
import com.nsure.integration.IntegrationService;
import com.nsure.integration.IntegrationViews;
import com.nsure.reporting.ReportViews.Breakdown;
import com.nsure.reporting.ReportViews.BreakdownRow;
import com.nsure.reporting.ReportViews.Metric;
import com.nsure.reporting.ReportViews.ReconItem;
import com.nsure.reporting.ReportViews.ReconLine;
import com.nsure.reporting.ReportViews.Section;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Sections for Individual business, insured lives and exposure, the subledger (reconciled) and integration health. */
@Configuration
public class CoreReportSections {

    static final String ACTIVE_LIVES_SQL = "select lc.full_name, lc.date_of_birth from ins_policy_life_cover lc join"
            + " ins_policy p on p.id = lc.policy_id where p.status in ('Active', 'Grace') and lc.life_status = 'ACTIVE' and";
    static final String HOLDER_LIVES_SQL = "select c.name, c.date_of_birth from ins_policy p join ins_customer c on"
            + " c.record_ref = p.customer_ref where p.status in ('Active', 'Grace') and not exists (select 1 from"
            + " ins_policy_life_cover lc where lc.policy_id = p.id) and";
    static final String GROUP_LIVES_SQL = "select concat(concat(m.first_name, ' '), m.last_name), m.date_of_birth"
            + " from grp_member m join ins_scheme s on s.id = m.scheme_id where m.member_status in ('ACTIVE',"
            + " 'PARTIALLY_ACCEPTED') and";

    @Bean
    ReportSection individualSection(ReportSql sql) {
        return f -> {
            ReportFilter.Sql stock = f.policies(null);
            List<BreakdownRow> policies = sql.rows("select p.status, count(*), sum(p.premium) from ins_policy p where"
                    + stock.where() + " group by p.status order by p.status", stock.params());
            List<BreakdownRow> products = sql.rows("select p.product, count(*), sum(p.amount) from ins_policy p where"
                    + " p.status in ('Active', 'Grace') and" + stock.where() + " group by p.product order by p.product",
                    stock.params());
            ReportFilter.Sql claimStock = f.viaPolicy("c.policy_ref", null);
            List<BreakdownRow> claims = sql.rows("select c.status, count(*), sum(c.payout_amount) from ins_claim c where"
                    + claimStock.where() + " group by c.status order by c.status", claimStock.params());
            ReportFilter.Sql issued = f.policies("p.cover_start_date");
            long newBusiness = sql.count("select count(*) from ins_policy p where p.cover_start_date is not null and"
                    + issued.where(), issued.params());
            ReportFilter.Sql paid = f.viaPolicy("c.policy_ref", "c.paid_at");
            BigDecimal claimsPaid = sql.amount("select coalesce(sum(c.payout_amount), 0) from ins_claim c where"
                    + " c.status in ('Paid', 'Closed') and" + paid.where(), paid.params());
            Set<String> lives = new HashSet<>(sql.persons(ACTIVE_LIVES_SQL + stock.where(), stock.params()));
            lives.addAll(sql.persons(HOLDER_LIVES_SQL + stock.where(), stock.params()));
            long active = countOf(policies, "Active") + countOf(policies, "Grace");
            long lapsed = countOf(policies, "Lapsed") + countOf(policies, "Active - No Cover");
            long openClaims = claims.stream()
                    .filter(r -> List.of("Initiated", "Under Review", "Recommended", "Approved", "Payout Sent",
                            "Payout Failed").contains(r.label()))
                    .mapToLong(BreakdownRow::count).sum();
            List<Metric> metrics = new ArrayList<>();
            metrics.add(Metric.count("Policies in force", active).defined("active_policies", "policies.inforce"));
            metrics.add(Metric.count("Insured lives in force", lives.size())
                    .tone(null, "Distinct persons, not policies").defined("active_lives", "lives.individual"));
            metrics.add(Metric.bwp("Sum assured in force", products.stream()
                    .map(r -> r.amount() == null ? BigDecimal.ZERO : r.amount()).reduce(BigDecimal.ZERO,
                            BigDecimal::add)).defined("benefit_exposure", "policies.inforce"));
            metrics.add(Metric.bwp("Monthly premium in force", amountOf(policies, "Active").add(amountOf(policies, "Grace")))
                    .defined("premium_in_force", "policies.inforce"));
            metrics.add(Metric.count("New business issued", newBusiness)
                    .tone(null, f.hasPeriod() ? "In the period" : "All time").defined("new_business", "policies.issued"));
            metrics.add(Metric.count("Lapsed or without cover", lapsed).tone(lapsed > 0 ? "watch" : "good", null)
                    .defined("lapsed_policies", "policies.lapsed"));
            metrics.add(Metric.count("Open claims", openClaims).tone(openClaims > 0 ? "watch" : "good", null)
                    .defined("open_claims", "claims.open"));
            metrics.add(Metric.bwp("Claims paid", claimsPaid).tone(null, f.hasPeriod() ? "Paid in the period" : null)
                    .defined("claims_paid", "claims.paid"));
            return new Section("individual", "Individual life", "INDIVIDUAL", 10, metrics,
                    List.of(new Breakdown("Policies by status", policies), new Breakdown("Claims by status", claims),
                            new Breakdown("Sum assured in force by product", products)),
                    LocalDateTime.now(), null, List.of("PERIOD", "PRODUCT", "CHANNEL"), List.of());
        };
    }

    /**
     * AT-SH13-01: insured persons counted once within each scope and across both, with benefit exposure reported
     * separately from lives.
     */
    @Bean
    ReportSection livesSection(ReportSql sql) {
        return f -> {
            ReportFilter.Sql stock = f.policies(null);
            ReportFilter.Sql members = f.members(null);
            Set<String> individual = new HashSet<>(sql.persons(ACTIVE_LIVES_SQL + stock.where(), stock.params()));
            individual.addAll(sql.persons(HOLDER_LIVES_SQL + stock.where(), stock.params()));
            Set<String> group = sql.persons(GROUP_LIVES_SQL + members.where(), members.params());
            Set<String> both = new HashSet<>(individual);
            both.retainAll(group);
            Set<String> all = new HashSet<>(individual);
            all.addAll(group);
            long memberRows = sql.count("select count(*) from grp_member m join ins_scheme s on s.id = m.scheme_id"
                    + " where m.member_status in ('ACTIVE', 'PARTIALLY_ACCEPTED') and" + members.where(),
                    members.params());
            BigDecimal individualExposure = sql.amount("select coalesce(sum(p.amount), 0) from ins_policy p where"
                    + " p.status in ('Active', 'Grace') and" + stock.where(), stock.params());
            BigDecimal groupExposure = sql.amount("select coalesce(sum(m.accepted_cover), 0) from grp_member m join"
                    + " ins_scheme s on s.id = m.scheme_id where m.member_status in ('ACTIVE', 'PARTIALLY_ACCEPTED') and"
                    + members.where(), members.params());
            BigDecimal pending = sql.amount("select coalesce(sum(m.pending_cover), 0) from grp_member m join"
                    + " ins_scheme s on s.id = m.scheme_id where m.member_status <> 'CEASED' and" + members.where(),
                    members.params());
            List<Metric> metrics = List.of(
                    Metric.count("Distinct insured persons", all.size())
                            .tone(null, "Each person once, individual and group together").defined("active_lives",
                                    "lives.all"),
                    Metric.count("Individual insured lives", individual.size()).defined("active_lives", "lives.individual"),
                    Metric.count("Group member lives", group.size())
                            .tone(null, memberRows + " membership rows").defined("active_lives", "lives.group"),
                    Metric.count("Covered in both", both.size()).tone(both.isEmpty() ? "good" : "watch",
                            "Counted once above"),
                    Metric.bwp("Individual sum assured in force", individualExposure).defined("benefit_exposure",
                            "policies.inforce"),
                    Metric.bwp("Group cover accepted", groupExposure).defined("benefit_exposure", "group.members"),
                    Metric.bwp("Group cover pending underwriting", pending).tone(pending.signum() > 0 ? "watch" : "good",
                            "Not shown as covered"));
            return new Section("lives", "Insured lives and exposure", "SHARED", 15, metrics, List.of(),
                    LocalDateTime.now(), null, List.of("PRODUCT", "CHANNEL", "SCHEME"), List.of());
        };
    }

    @Bean
    ReportSection financeSection(FinanceService finance, ReportSql sql, ObjectMapper mapper) {
        return f -> {
            FinanceViews.FinanceSummary s = finance.summary();
            List<ReconLine> recon = List.of(
                    line(sql, mapper, "Individual premium collected",
                            sql.amount("select coalesce(sum(amount), 0) from premium_payment where status = 'SUCCESSFUL'"),
                            posted(sql, "4000", false), List.of("PremiumReceived", "PaymentReturned"),
                            "Premium receipts not reversed vs account 4000"),
                    line(sql, mapper, "Individual claims paid",
                            sql.amount("select coalesce(sum(payout_amount), 0) from ins_claim where status in ('Paid', 'Closed')"),
                            sql.amount("select coalesce(sum(l.debit - l.credit), 0) from fin_journal_line l join fin_journal j"
                                    + " on j.id = l.journal_id where l.account_code = '5000' and j.event_type = 'ClaimPaid'"),
                            List.of("ClaimPaid"), "Paid claims vs ClaimPaid postings to account 5000"),
                    line(sql, mapper, "Commission",
                            sql.amount("select coalesce(sum(amount), 0) from dist_commission"),
                            posted(sql, "5100", true), List.of("CommissionAccrued", "CommissionReversed"),
                            "Commission accrued net of clawbacks vs account 5100"),
                    line(sql, mapper, "Reinsurance premium ceded",
                            sql.amount("select coalesce(sum(ceded_premium), 0) from ri_cession"),
                            posted(sql, "5200", true), List.of("RiskCeded"), "Cessions vs account 5200"));
            long breaks = recon.stream().filter(l -> "BREAK".equals(l.status())).count();
            List<Metric> metrics = List.of(
                    Metric.bwp("Premium income posted", s.premiumIncome()).defined("premium_collected", null),
                    Metric.bwp("Claims paid posted", s.claimsPaid()).defined("claims_paid", null),
                    Metric.bwp("Commission expense", s.commissionExpense()),
                    Metric.bwp("Reinsurance premium ceded", s.reinsuranceCeded()).defined("ceded_premium", null),
                    Metric.bwp("Suspense balance", s.suspense())
                            .tone(s.suspense().signum() > 0 ? "alert" : "good", "Cash received but not applied"),
                    Metric.count("Unposted events", s.openExceptions())
                            .tone(s.openExceptions() > 0 ? "alert" : "good", "Financial exceptions")
                            .defined(null, "finance.unposted"),
                    Metric.count("Journals awaiting GL export", s.unexportedJournals())
                            .tone(s.unexportedJournals() > 0 ? "watch" : "good", null),
                    Metric.count("Reconciliation breaks", breaks).tone(breaks > 0 ? "alert" : "good",
                            "Operational vs subledger; see below"));
            return new Section("finance", "Insurance subledger", "SHARED", 30, metrics, List.of(), s.asOf(), null,
                    List.of(), recon);
        };
    }

    private static BigDecimal posted(ReportSql sql, String account, boolean debitNormal) {
        return sql.amount("select coalesce(sum(" + (debitNormal ? "debit - credit" : "credit - debit") + "), 0)"
                + " from fin_journal_line where account_code = ?", account);
    }

    /**
     * AT-SH13-02: the operational total against the subledger, and every monetary event of these types with no
     * journal, with why (still queued, failed to post, or held as a financial exception).
     */
    private static ReconLine line(ReportSql sql, ObjectMapper mapper, String measure, BigDecimal operational,
            BigDecimal posted, List<String> eventTypes, String basis) {
        List<ReconItem> items = new ArrayList<>();
        String in = String.join(",", eventTypes.stream().map(t -> "?").toList());
        sql.jdbc().query("select e.event_type, e.aggregate_ref, e.payload, (select max(d.status) from int_event_delivery d"
                + " where d.outbox_event_id = e.id and d.handler_name = 'finance.subledger') delivery,"
                + " (select max(x.reason) from fin_exception x where x.event_key = e.event_id and x.status = 'OPEN')"
                + " reason from int_outbox_event e where e.event_type in (" + in + ") and not exists (select 1 from"
                + " fin_journal j where j.event_key = e.event_id) order by e.id desc", rs -> {
                    if (items.size() >= 200) {
                        return;
                    }
                    String delivery = rs.getString("delivery");
                    String reason = rs.getString("reason");
                    String state = reason != null ? "EXCEPTION" : delivery == null ? "NOT_ROUTED"
                            : "FAILED".equals(delivery) ? "FAILED" : "PENDING".equals(delivery) ? "UNPOSTED" : "EXCLUDED";
                    items.add(new ReconItem(rs.getString("event_type"), rs.getString("aggregate_ref"),
                            amount(mapper, rs.getString("payload")), state, reason != null ? reason
                                    : "PENDING".equals(delivery) ? "Waiting for the subledger to post it"
                                    : "FAILED".equals(delivery) ? "Posting failed: replay it from the Integration monitor"
                                    : "Delivered but no journal (no amount to post)"));
                }, eventTypes.toArray());
        BigDecimal difference = operational.subtract(posted);
        String status = difference.signum() == 0 ? "RECONCILED"
                : items.isEmpty() ? "BREAK" : "EXPLAINED";
        return new ReconLine(measure, operational, posted, difference, status, basis, items);
    }

    private static BigDecimal amount(ObjectMapper mapper, String payload) {
        try {
            JsonNode node = mapper.readTree(payload);
            return node.hasNonNull("amount") ? new BigDecimal(node.get("amount").asText()) : null;
        } catch (Exception ex) {
            return null;
        }
    }

    @Bean
    ReportSection integrationSection(IntegrationService integration) {
        return f -> {
            IntegrationViews.DashboardView d = integration.summary();
            long pending = d.deliveriesByStatus().getOrDefault("PENDING", 0L);
            List<Metric> metrics = List.of(
                    Metric.count("Business events", d.events()),
                    Metric.count("Deliveries pending", pending).tone(pending > 0 ? "watch" : "good",
                            d.oldestPending() == null ? null : "Oldest from " + d.oldestPending().withNano(0)),
                    Metric.count("Deliveries dead-lettered", d.failed()).tone(d.failed() > 0 ? "alert" : "good",
                            "Replay from the integration monitor"),
                    Metric.count("Payment notifications in suspense", d.inboundByStatus().getOrDefault("SUSPENSE", 0L)),
                    Metric.count("Interfaces unavailable", d.interfacesDown().size())
                            .tone(d.interfacesDown().isEmpty() ? "good" : "alert", String.join(", ", d.interfacesDown())));
            List<BreakdownRow> inbound = d.inboundByStatus().entrySet().stream()
                    .map(e -> new BreakdownRow(e.getKey(), e.getValue(), null)).toList();
            return new Section("integration", "Integration health", "SHARED", 90, metrics,
                    List.of(new Breakdown("Inbound messages", inbound)), d.asOf(), null);
        };
    }
}
