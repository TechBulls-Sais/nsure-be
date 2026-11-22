package com.nsure.reporting;

import static com.nsure.reporting.ReportSql.amountOf;
import static com.nsure.reporting.ReportSql.countOf;
import static com.nsure.reporting.ReportSql.strip;

import com.nsure.reporting.ReportViews.Breakdown;
import com.nsure.reporting.ReportViews.BreakdownRow;
import com.nsure.reporting.ReportViews.Metric;
import com.nsure.reporting.ReportViews.Section;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Sections for Group business, distribution, reinsurance, the service desk and customer communications. */
@Configuration
public class ModuleReportSections {

    @Bean
    ReportSection groupSection(ReportSql sql) {
        return f -> {
            ReportFilter.Sql stock = f.members(null);
            String from = " from grp_member m join ins_scheme s on s.id = m.scheme_id where";
            List<BreakdownRow> members = sql.rows("select m.member_status, count(*), sum(m.accepted_cover)" + from
                    + stock.where() + " group by m.member_status order by m.member_status", stock.params());
            List<BreakdownRow> schemes = sql.rows("select s.name, count(*), sum(m.accepted_cover)" + from
                    + " m.member_status <> 'CEASED' and" + stock.where() + " group by s.name order by s.name",
                    stock.params());
            ReportFilter.Sql scheme = new ReportFilter(null, null, null, null, f.scheme()).members(null);
            List<BreakdownRow> invoices = sql.rows("select i.invoice_status, count(*), sum(i.outstanding) from"
                    + " grp_invoice i join ins_scheme s on s.id = i.scheme_id where" + scheme.where()
                    + " group by i.invoice_status order by i.invoice_status", scheme.params());
            ReportFilter.Sql billedIn = f.members("i.issued_at");
            BigDecimal invoiced = sql.amount("select coalesce(sum(i.amount), 0) from grp_invoice i join ins_scheme s on"
                    + " s.id = i.scheme_id where" + billedIn.where(), billedIn.params());
            BigDecimal outstanding = sql.amount("select coalesce(sum(i.outstanding), 0) from grp_invoice i join"
                    + " ins_scheme s on s.id = i.scheme_id where" + scheme.where(), scheme.params());
            ReportFilter.Sql joined = f.members("m.join_date");
            long joiners = sql.count("select count(*)" + from + joined.where(), joined.params());
            long lives = sql.persons(CoreReportSections.GROUP_LIVES_SQL + stock.where(), stock.params()).size();
            long pending = countOf(members, "UNDERWRITING_PENDING");
            List<Metric> metrics = List.of(
                    Metric.count("Schemes", sql.count("select count(*) from ins_scheme s where" + scheme.where(),
                            scheme.params())),
                    Metric.count("Covered member lives", lives).tone(null, "Distinct persons with accepted cover")
                            .defined("active_lives", "group.members"),
                    Metric.bwp("Accepted cover", members.stream().filter(r -> !r.label().equals("CEASED"))
                            .map(r -> r.amount() == null ? BigDecimal.ZERO : r.amount())
                            .reduce(BigDecimal.ZERO, BigDecimal::add)).defined("benefit_exposure", "group.members"),
                    Metric.count("Members pending underwriting", pending).tone(pending > 0 ? "watch" : "good",
                            "Never shown as covered").defined(null, "group.pending"),
                    Metric.count("Joiners", joiners).tone(null, f.hasPeriod() ? "Joined in the period" : "All time"),
                    Metric.bwp("Invoiced", invoiced).tone(null, f.hasPeriod() ? "Issued in the period" : null),
                    Metric.bwp("Sponsor receivable outstanding", outstanding)
                            .tone(outstanding.signum() > 0 ? "watch" : "good", null));
            return new Section("group", "Group schemes", "GROUP", 20, metrics,
                    List.of(new Breakdown("Members by status", members), new Breakdown("Cover by scheme", schemes),
                            new Breakdown("Invoices by status (outstanding)", invoices)),
                    LocalDateTime.now(), null, List.of("PERIOD", "SCHEME"), List.of());
        };
    }

    @Bean
    ReportSection distributionSection(ReportSql sql) {
        return f -> {
            StringBuilder w = new StringBuilder(" 1 = 1");
            List<Object> a = new ArrayList<>();
            if (f.product() != null) {
                w.append(" and c.product = ?");
                a.add(f.product());
            }
            if (f.intermediary() != null) {
                w.append(ReportFilter.DIRECT.equals(f.intermediary()) ? " and 1 = 0" : " and i.record_ref = ?");
                if (!ReportFilter.DIRECT.equals(f.intermediary())) {
                    a.add(f.intermediary());
                }
            }
            ReportFilter.Sql period = f.period("c.created_at");
            w.append(" and").append(period.where());
            a.addAll(period.args());
            String from = " from dist_commission c join dist_intermediary i on i.id = c.intermediary_id where" + w;
            Object[] args = a.toArray();
            List<BreakdownRow> byStatus = sql.rows("select c.status, count(*), sum(c.amount)" + from
                    + " group by c.status order by c.status", args);
            List<BreakdownRow> byIntermediary = sql.rows("select i.name, count(*), sum(c.amount)" + from
                    + " group by i.name order by i.name", args);
            List<BreakdownRow> byKind = sql.rows("select c.line_kind, count(*), sum(c.amount)" + from
                    + " group by c.line_kind", args);
            return new Section("distribution", "Distribution and commission", "SHARED", 40, List.of(
                    Metric.bwp("Commission accrued, unpaid", amountOf(byStatus, "ACCRUED")
                            .add(amountOf(byStatus, "STATEMENTED"))).defined(null, "commission.unpaid"),
                    Metric.bwp("Commission paid", amountOf(byStatus, "PAID")),
                    Metric.bwp("Clawed back", amountOf(byKind, "CLAWBACK").abs())),
                    List.of(new Breakdown("Commission by intermediary", byIntermediary)), LocalDateTime.now(), null,
                    List.of("PERIOD", "PRODUCT", "CHANNEL"), List.of());
        };
    }

    @Bean
    ReportSection reinsuranceSection(ReportSql sql) {
        return f -> {
            StringBuilder w = new StringBuilder(" 1 = 1");
            List<Object> a = new ArrayList<>();
            if (f.product() != null) {
                w.append(" and cs.product = ?");
                a.add(f.product());
            }
            ReportFilter.Sql period = f.period("cs.created_at");
            w.append(" and").append(period.where());
            a.addAll(period.args());
            Object[] args = a.toArray();
            Object[] ceded = sql.jdbc().queryForObject("select count(*), coalesce(sum(cs.premium), 0),"
                    + " coalesce(sum(cs.ceded_premium), 0) from ri_cession cs where" + w, (rs, n) -> new Object[] {
                            rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3)}, args);
            List<BreakdownRow> recoveries = sql.rows("select r.status, count(*), sum(r.recovery_amount) from ri_recovery r"
                    + " group by r.status order by r.status");
            List<BreakdownRow> byTreaty = sql.rows("select t.name, count(*), sum(cs.ceded_premium) from ri_cession cs"
                    + " join ri_treaty t on t.id = cs.treaty_id where" + w + " group by t.name order by t.name", args);
            BigDecimal gross = ReportSql.decimal(ceded[1]);
            BigDecimal cededPremium = ReportSql.decimal(ceded[2]);
            return new Section("reinsurance", "Reinsurance", "SHARED", 50, List.of(
                    Metric.count("Cessions", (Long) ceded[0]),
                    Metric.bwp("Gross premium on ceded risks", gross),
                    Metric.bwp("Ceded premium", cededPremium).defined("ceded_premium", null),
                    Metric.bwp("Retained premium", gross.subtract(cededPremium)),
                    Metric.bwp("Recoveries expected", amountOf(recoveries, "EXPECTED"))),
                    List.of(new Breakdown("Ceded premium by treaty", byTreaty)), LocalDateTime.now(), null,
                    List.of("PERIOD", "PRODUCT"), List.of());
        };
    }

    @Bean
    ReportSection serviceSection(ReportSql sql) {
        return f -> {
            LocalDateTime now = LocalDateTime.now();
            List<BreakdownRow> byStatus = strip(sql.rows("select c.status, count(*), count(*) from svc_case c"
                    + " group by c.status order by c.status"));
            List<BreakdownRow> byType = sql.rows("select c.case_type, count(*), count(*) from svc_case c"
                    + " where c.status not in ('RESOLVED', 'CLOSED') group by c.case_type");
            // A paused case's clock is stopped, so only running cases past due count as breached.
            long breached = sql.count("select count(*) from svc_case c where c.status in ('OPEN', 'IN_PROGRESS')"
                    + " and c.due_at < ?", now);
            long open = byStatus.stream().filter(r -> !List.of("RESOLVED", "CLOSED").contains(r.label()))
                    .mapToLong(BreakdownRow::count).sum();
            return new Section("service", "Service desk", "SHARED", 60, List.of(
                    Metric.count("Open cases", open),
                    Metric.count("Open complaints", countOf(byType, "COMPLAINT")),
                    Metric.count("SLA breached", breached).tone(breached > 0 ? "alert" : "good", null)
                            .defined(null, "service.breached"),
                    Metric.count("Paused", countOf(byStatus, "PAUSED"))),
                    List.of(new Breakdown("Cases by status", byStatus)), now, null);
        };
    }

    @Bean
    ReportSection communicationSection(ReportSql sql) {
        return f -> {
            ReportFilter.Sql period = f.period("m.created_at");
            List<BreakdownRow> byStatus = strip(sql.rows("select m.status, count(*), count(*) from com_message m where"
                    + period.where() + " group by m.status order by m.status", period.params()));
            List<BreakdownRow> byChannel = strip(sql.rows("select m.channel, count(*), count(*) from com_message m where"
                    + period.where() + " group by m.channel order by m.channel", period.params()));
            long failed = countOf(byStatus, "FAILED");
            return new Section("communications", "Customer communications", "SHARED", 70, List.of(
                    Metric.count("Delivered", countOf(byStatus, "DELIVERED") + countOf(byStatus, "READ")),
                    Metric.count("Queued", countOf(byStatus, "QUEUED")),
                    Metric.count("Failed", failed).tone(failed > 0 ? "alert" : "good", "Retry from Communications"),
                    Metric.count("Suppressed (consent)", countOf(byStatus, "SUPPRESSED"))),
                    List.of(new Breakdown("Messages by status", byStatus), new Breakdown("Messages by channel", byChannel)),
                    LocalDateTime.now(), null, List.of("PERIOD"), List.of());
        };
    }
}
