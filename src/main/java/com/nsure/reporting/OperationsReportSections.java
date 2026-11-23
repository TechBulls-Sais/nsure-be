package com.nsure.reporting;

import static com.nsure.reporting.ReportSql.countOf;
import static com.nsure.reporting.ReportSql.percent;
import static com.nsure.reporting.ReportSql.strip;

import com.nsure.reporting.ReportViews.Breakdown;
import com.nsure.reporting.ReportViews.BreakdownRow;
import com.nsure.reporting.ReportViews.Metric;
import com.nsure.reporting.ReportViews.Section;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * NS-SH13-001 sections the first dashboard lacked: the new-business funnel with the underwriting and evidence queue,
 * individual arrears and collections, and documents. Each figure is computed from the transaction tables.
 */
@Configuration
public class OperationsReportSections {

    @Bean
    ReportSection newBusinessSection(ReportSql sql) {
        return f -> {
            StringBuilder qw = new StringBuilder(" 1 = 1");
            List<Object> qa = new ArrayList<>();
            if (f.product() != null) {
                qw.append(" and q.product = ?");
                qa.add(f.product());
            }
            ReportFilter.Sql qp = f.period("q.created_at");
            qw.append(" and").append(qp.where());
            qa.addAll(qp.args());
            long quotes = sql.count("select count(distinct q.record_ref) from nb_quote q where" + qw, qa.toArray());
            long quotesAccepted = sql.count("select count(distinct q.record_ref) from nb_quote q where q.status = 'ACCEPTED'"
                    + " and" + qw, qa.toArray());
            // The application cohort: in the period means submitted in it (a draft has no submission date yet).
            ReportFilter.Sql apps = f.applications(f.hasPeriod() ? "a.submitted_at" : null);
            String cohort = " from ins_application a where" + apps.where();
            Object[] args = apps.params();
            long applications = sql.count("select count(*)" + cohort, args);
            long submitted = sql.count("select count(*)" + cohort + " and a.submitted_at is not null", args);
            long underwriting = sql.count("select count(*)" + cohort + " and exists (select 1 from uw_case u where"
                    + " u.application_ref = a.record_ref)", args);
            long offered = sql.count("select count(*)" + cohort + " and exists (select 1 from uw_case u where"
                    + " u.application_ref = a.record_ref and (u.decision is not null or u.offer_status is not null))", args);
            long accepted = sql.count("select count(*)" + cohort + " and (a.status in ('Ready to issue', 'Issued')"
                    + " or exists (select 1 from uw_case u where u.application_ref = a.record_ref and u.offer_status ="
                    + " 'ACCEPTED'))", args);
            long issued = sql.count("select count(*)" + cohort + " and exists (select 1 from ins_policy p where"
                    + " p.application_ref = a.record_ref)", args);
            List<BreakdownRow> funnel = List.of(
                    new BreakdownRow("1 Quotes", quotes, null),
                    new BreakdownRow("2 Applications", applications, null),
                    new BreakdownRow("3 Submitted", submitted, null),
                    new BreakdownRow("4 In underwriting", underwriting, null),
                    new BreakdownRow("5 Decided or offered", offered, null),
                    new BreakdownRow("6 Accepted", accepted, null),
                    new BreakdownRow("7 Issued", issued, null));
            List<BreakdownRow> outcomes = strip(sql.rows("select a.status, count(*), count(*)" + cohort
                    + " group by a.status order by a.status", args));
            // The underwriting and evidence queue now (stock, so no period).
            List<BreakdownRow> cases = strip(sql.rows("select u.status, count(*), count(*) from uw_case u group by u.status"
                    + " order by u.status"));
            List<LocalDateTime> openSince = sql.jdbc().query("select u.created_at from uw_case u where u.status not in"
                    + " ('ACCEPTED', 'DECLINED', 'POSTPONED', 'WITHDRAWN')", (rs, n) -> rs.getTimestamp(1)
                    .toLocalDateTime());
            long openCases = openSince.size();
            long averageAge = openSince.isEmpty() ? 0 : openSince.stream()
                    .mapToLong(t -> Duration.between(t, LocalDateTime.now()).toDays()).sum() / openCases;
            long evidence = sql.count("select count(*) from uw_evidence e where e.status = 'REQUESTED'");
            long overdue = sql.count("select count(*) from uw_evidence e where e.status = 'REQUESTED' and e.due_date < ?",
                    LocalDate.now());
            List<Metric> metrics = List.of(
                    Metric.count("Quotes", quotes).tone(null, quotesAccepted + " accepted").defined("quotes", "nb.quotes"),
                    Metric.count("Applications submitted", submitted).defined("applications_submitted", "nb.submitted"),
                    Metric.count("Policies issued from applications", issued).defined("new_business", "nb.issued"),
                    Metric.percent("Submitted to issued", percent(issued, submitted))
                            .tone(null, issued + " of " + submitted).defined("conversion_rate", null),
                    Metric.count("Open underwriting cases", openCases).tone(openCases > 0 ? "watch" : "good",
                            "Average age " + averageAge + " days").defined("uw_queue", "uw.open"),
                    Metric.count("Evidence outstanding", evidence).tone(overdue > 0 ? "alert" : evidence > 0 ? "watch"
                            : "good", overdue + " overdue").defined("evidence_outstanding", "uw.evidence"));
            return new Section("newbusiness", "New business and underwriting", "INDIVIDUAL", 12, metrics,
                    List.of(new Breakdown("Funnel", funnel), new Breakdown("Applications by status", outcomes),
                            new Breakdown("Underwriting cases by status", cases)),
                    LocalDateTime.now(), null, List.of("PERIOD", "PRODUCT", "CHANNEL"), List.of());
        };
    }

    @Bean
    ReportSection collectionsSection(ReportSql sql) {
        return f -> {
            LocalDate today = LocalDate.now();
            ReportFilter.Sql due = f.viaPolicy("i.policy_ref", f.hasPeriod() ? "i.due_date" : null);
            Object[] billed = sql.jdbc().queryForObject("select count(*), coalesce(sum(i.billed_amount), 0),"
                    + " coalesce(sum(case when i.status in ('PAID', 'WAIVED') then i.billed_amount else 0 end), 0)"
                    + " from bil_instalment i where i.status <> 'CANCELLED' and" + due.where(), (rs, n) ->
                    new Object[] {rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3)}, due.params());
            ReportFilter.Sql stock = f.viaPolicy("i.policy_ref", null);
            List<Object> overdueArgs = new ArrayList<>(List.of(today));
            overdueArgs.addAll(stock.args());
            Object[] overdue = sql.jdbc().queryForObject("select count(*), coalesce(sum(i.billed_amount), 0) from"
                    + " bil_instalment i where i.status in ('DUE', 'PART_PAID') and i.due_date < ? and" + stock.where(),
                    (rs, n) -> new Object[] {rs.getLong(1), rs.getBigDecimal(2)}, overdueArgs.toArray());
            ReportFilter.Sql paid = f.viaPolicy("pp.policy_ref", "pp.paid_at");
            BigDecimal collected = sql.amount("select coalesce(sum(pp.amount), 0) from premium_payment pp where"
                    + " pp.status = 'SUCCESSFUL' and" + paid.where(), paid.params());
            ReportFilter.Sql policies = f.policies(null);
            List<Object> graceArgs = new ArrayList<>(List.of(today, today));
            graceArgs.addAll(policies.args());
            long inGrace = sql.count("select count(*) from ins_policy p where (p.status = 'Grace' or (p.status = 'Active'"
                    + " and p.next_premium_due < ?)) and p.grace_period_end >= ? and" + policies.where(), graceArgs.toArray());
            long noCover = sql.count("select count(*) from ins_policy p where p.status = 'Active - No Cover' and"
                    + policies.where(), policies.params());
            long suspense = sql.count("select count(*) from int_inbound_message where status = 'SUSPENSE'");
            List<BreakdownRow> byStatus = sql.rows("select i.status, count(*), sum(i.billed_amount) from bil_instalment i"
                    + " where" + due.where() + " group by i.status order by i.status", due.params());
            BigDecimal billedAmount = ReportSql.decimal(billed[1]);
            List<Metric> metrics = List.of(
                    Metric.bwp("Premium due", billedAmount).tone(null, billed[0] + " instalment(s)"
                            + (f.hasPeriod() ? " due in the period" : "")).defined("premium_due", "collections.due"),
                    Metric.bwp("Premium collected", collected).tone(null, f.hasPeriod() ? "Received in the period" : null)
                            .defined("premium_collected", "collections.collected"),
                    Metric.percent("Collection rate", percent(ReportSql.decimal(billed[2]), billedAmount))
                            .tone(null, "Instalments paid or waived ÷ billed").defined("collection_rate", null),
                    Metric.bwp("Arrears", ReportSql.decimal(overdue[1])).tone(((Long) overdue[0]) > 0 ? "alert" : "good",
                            overdue[0] + " overdue instalment(s)").defined("arrears", "collections.overdue"),
                    Metric.count("Policies in grace", inGrace).tone(inGrace > 0 ? "watch" : "good", "Cover continues")
                            .defined(null, "policies.grace"),
                    Metric.count("Policies without cover", noCover).tone(noCover > 0 ? "alert" : "good", null)
                            .defined("lapsed_policies", "policies.lapsed"),
                    Metric.count("Receipts in suspense", suspense).tone(suspense > 0 ? "watch" : "good", null));
            return new Section("collections", "Arrears and collections", "INDIVIDUAL", 14, metrics,
                    List.of(new Breakdown("Instalments by status", byStatus)), LocalDateTime.now(), null,
                    List.of("PERIOD", "PRODUCT", "CHANNEL"), List.of());
        };
    }

    @Bean
    ReportSection documentsSection(ReportSql sql) {
        return f -> {
            List<BreakdownRow> policyDocs = strip(sql.rows("select d.status, count(*), count(*) from ins_policy_document d"
                    + " group by d.status order by d.status"));
            List<BreakdownRow> claimDocs = strip(sql.rows("select coalesce(d.scan_status, 'NOT SCANNED'), count(*),"
                    + " count(*) from ins_claim_document d group by d.scan_status order by d.scan_status"));
            List<BreakdownRow> quarantine = strip(sql.rows("select q.status, count(*), count(*) from doc_quarantine q"
                    + " group by q.status order by q.status"));
            long holds = sql.count("select count(*) from doc_control where legal_hold = TRUE");
            long held = countOf(quarantine, "PENDING") + countOf(quarantine, "QUARANTINED");
            return new Section("documents", "Documents", "SHARED", 65, List.of(
                    Metric.count("Policy documents current", countOf(policyDocs, "CURRENT")),
                    Metric.count("Superseded versions kept", countOf(policyDocs, "SUPERSEDED")),
                    Metric.count("Claim documents", claimDocs.stream().mapToLong(BreakdownRow::count).sum()),
                    Metric.count("Uploads held in quarantine", held).tone(held > 0 ? "watch" : "good", "Awaiting release"),
                    Metric.count("Documents on legal hold", holds)),
                    List.of(new Breakdown("Policy documents by status", policyDocs),
                            new Breakdown("Claim documents by scan status", claimDocs),
                            new Breakdown("Quarantine by status", quarantine)),
                    LocalDateTime.now(), null);
        };
    }

    /**
     * Actuarial indicators whose formulas need actuarial approval (NS-SH13-003): 13-month persistency of the monthly
     * issue cohort and the claims ratio. Each figure carries its dictionary status, so a figure computed from a
     * definition awaiting approval is shown as such.
     */
    @Bean
    ReportSection actuarialSection(ReportSql sql) {
        return f -> {
            LocalDate cutoff = f.to() == null ? LocalDate.now() : f.to();
            java.time.YearMonth cohort = java.time.YearMonth.from(cutoff).minusMonths(13);
            ReportFilter.Sql policies = f.policies(null);
            List<Object> args = new ArrayList<>(List.of(cohort.atDay(1), cohort.plusMonths(1).atDay(1)));
            args.addAll(policies.args());
            String from = " from ins_policy p where p.cover_start_date >= ? and p.cover_start_date < ? and"
                    + policies.where();
            long issued = sql.count("select count(*)" + from, args.toArray());
            long inForce = sql.count("select count(*)" + from + " and p.status in ('Active', 'Grace')", args.toArray());
            ReportFilter.Sql paid = f.viaPolicy("c.policy_ref", "c.paid_at");
            BigDecimal claims = sql.amount("select coalesce(sum(c.payout_amount), 0) from ins_claim c where"
                    + " c.status in ('Paid', 'Closed') and" + paid.where(), paid.params());
            ReportFilter.Sql received = f.viaPolicy("pp.policy_ref", "pp.paid_at");
            BigDecimal premium = sql.amount("select coalesce(sum(pp.amount), 0) from premium_payment pp where"
                    + " pp.status = 'SUCCESSFUL' and" + received.where(), received.params());
            return new Section("actuarial", "Actuarial indicators", "INDIVIDUAL", 16, List.of(
                    Metric.percent("Persistency (13-month)", percent(inForce, issued))
                            .tone(null, issued == 0 ? "No policies issued in " + cohort : inForce + " of " + issued
                                    + " issued in " + cohort + " still in force").defined("persistency_13m",
                                    "policies.inforce"),
                    Metric.percent("Claims ratio", percent(claims, premium))
                            .tone(null, "Claims paid ÷ premium collected" + (f.hasPeriod() ? " in the period" : ""))
                            .defined("claims_ratio", "claims.paid")),
                    List.of(), LocalDateTime.now(), null, List.of("PERIOD", "PRODUCT", "CHANNEL"), List.of());
        };
    }
}
