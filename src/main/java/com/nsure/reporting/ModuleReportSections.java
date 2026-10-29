package com.nsure.reporting;

import static com.nsure.reporting.CoreReportSections.amountOf;
import static com.nsure.reporting.CoreReportSections.countOf;
import static com.nsure.reporting.CoreReportSections.decimal;
import static com.nsure.reporting.CoreReportSections.rows;

import com.nsure.reporting.ReportViews.Breakdown;
import com.nsure.reporting.ReportViews.BreakdownRow;
import com.nsure.reporting.ReportViews.Metric;
import com.nsure.reporting.ReportViews.Section;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Sections for Group business, distribution, reinsurance, the service desk and customer communications. */
@Configuration
public class ModuleReportSections {

    @Bean
    ReportSection groupSection(EntityManager em) {
        return () -> {
            List<BreakdownRow> members = rows(em, "select m.status, count(m), sum(m.acceptedCover) from SchemeMember m"
                    + " group by m.status order by m.status");
            List<BreakdownRow> invoices = rows(em, "select i.status, count(i), sum(i.outstanding) from SchemeInvoice i"
                    + " group by i.status order by i.status");
            List<BreakdownRow> schemes = rows(em, "select m.scheme.name, count(m), sum(m.acceptedCover) from SchemeMember m"
                    + " where m.status <> 'CEASED' group by m.scheme.name order by m.scheme.name");
            Object[] billed = em.createQuery("select sum(i.amount), sum(i.paidAmount), sum(i.outstanding)"
                    + " from SchemeInvoice i", Object[].class).getSingleResult();
            long pending = countOf(members, "UNDERWRITING_PENDING");
            BigDecimal outstanding = decimal(billed[2]);
            List<Metric> metrics = List.of(
                    Metric.count("Schemes", em.createQuery("select count(s) from GroupScheme s", Long.class)
                            .getSingleResult()),
                    Metric.count("Active member lives", countOf(members, "ACTIVE") + countOf(members, "PARTIALLY_ACCEPTED")
                            + pending),
                    Metric.bwp("Authorised cover", members.stream().filter(r -> !r.label().equals("CEASED"))
                            .map(BreakdownRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add)),
                    Metric.count("Members pending underwriting", pending).tone(pending > 0 ? "watch" : "good", null),
                    Metric.bwp("Invoiced", decimal(billed[0])),
                    Metric.bwp("Sponsor receivable outstanding", outstanding)
                            .tone(outstanding.signum() > 0 ? "watch" : "good", null));
            return new Section("group", "Group schemes", "GROUP", 20, metrics,
                    List.of(new Breakdown("Members by status", members), new Breakdown("Cover by scheme", schemes),
                            new Breakdown("Invoices by status (outstanding)", invoices)),
                    LocalDateTime.now(), null);
        };
    }

    @Bean
    ReportSection distributionSection(EntityManager em) {
        return () -> {
            List<BreakdownRow> byStatus = rows(em, "select c.status, count(c), sum(c.amount) from Commission c"
                    + " group by c.status order by c.status");
            List<BreakdownRow> byIntermediary = rows(em, "select c.intermediary.name, count(c), sum(c.amount)"
                    + " from Commission c group by c.intermediary.name order by c.intermediary.name");
            List<BreakdownRow> byKind = rows(em, "select c.kind, count(c), sum(c.amount) from Commission c group by c.kind");
            return new Section("distribution", "Distribution and commission", "SHARED", 40, List.of(
                    Metric.bwp("Commission accrued, unpaid", amountOf(byStatus, "ACCRUED")
                            .add(amountOf(byStatus, "STATEMENTED"))),
                    Metric.bwp("Commission paid", amountOf(byStatus, "PAID")),
                    Metric.bwp("Clawed back", amountOf(byKind, "CLAWBACK").abs())),
                    List.of(new Breakdown("Commission by intermediary", byIntermediary)), LocalDateTime.now(), null);
        };
    }

    @Bean
    ReportSection reinsuranceSection(EntityManager em) {
        return () -> {
            Object[] ceded = em.createQuery("select count(c), sum(c.premium), sum(c.cededPremium) from Cession c",
                    Object[].class).getSingleResult();
            List<BreakdownRow> recoveries = rows(em, "select r.status, count(r), sum(r.recoveryAmount) from Recovery r"
                    + " group by r.status order by r.status");
            List<BreakdownRow> byTreaty = rows(em, "select c.treaty.name, count(c), sum(c.cededPremium) from Cession c"
                    + " group by c.treaty.name order by c.treaty.name");
            BigDecimal gross = decimal(ceded[1]);
            BigDecimal cededPremium = decimal(ceded[2]);
            return new Section("reinsurance", "Reinsurance", "SHARED", 50, List.of(
                    Metric.count("Cessions", ((Number) ceded[0]).longValue()),
                    Metric.bwp("Gross premium on ceded risks", gross),
                    Metric.bwp("Ceded premium", cededPremium),
                    Metric.bwp("Retained premium", gross.subtract(cededPremium)),
                    Metric.bwp("Recoveries expected", amountOf(recoveries, "EXPECTED"))),
                    List.of(new Breakdown("Ceded premium by treaty", byTreaty)), LocalDateTime.now(), null);
        };
    }

    @Bean
    ReportSection serviceSection(EntityManager em) {
        return () -> {
            LocalDateTime now = LocalDateTime.now();
            List<BreakdownRow> byStatus = rows(em, "select c.status, count(c), count(c) from ServiceCase c"
                    + " group by c.status order by c.status");
            List<BreakdownRow> byType = rows(em, "select c.caseType, count(c), count(c) from ServiceCase c"
                    + " where c.status not in ('RESOLVED', 'CLOSED') group by c.caseType");
            // A paused case's clock is stopped, so only running cases past due count as breached.
            long breached = em.createQuery("select count(c) from ServiceCase c where c.status in ('OPEN', 'IN_PROGRESS')"
                    + " and c.dueAt < :now", Long.class).setParameter("now", now).getSingleResult();
            long open = byStatus.stream().filter(r -> !List.of("RESOLVED", "CLOSED").contains(r.label()))
                    .mapToLong(BreakdownRow::count).sum();
            return new Section("service", "Service desk", "SHARED", 60, List.of(
                    Metric.count("Open cases", open),
                    Metric.count("Open complaints", countOf(byType, "COMPLAINT")),
                    Metric.count("SLA breached", breached).tone(breached > 0 ? "alert" : "good", null),
                    Metric.count("Paused", countOf(byStatus, "PAUSED"))),
                    List.of(new Breakdown("Cases by status", strip(byStatus))), now, null);
        };
    }

    @Bean
    ReportSection communicationSection(EntityManager em) {
        return () -> {
            List<BreakdownRow> byStatus = strip(rows(em, "select m.status, count(m), count(m) from CommunicationMessage m"
                    + " group by m.status order by m.status"));
            List<BreakdownRow> byChannel = strip(rows(em, "select m.channel, count(m), count(m) from CommunicationMessage m"
                    + " group by m.channel order by m.channel"));
            long failed = countOf(byStatus, "FAILED");
            return new Section("communications", "Customer communications", "SHARED", 70, List.of(
                    Metric.count("Delivered", countOf(byStatus, "DELIVERED")),
                    Metric.count("Queued", countOf(byStatus, "QUEUED")),
                    Metric.count("Failed", failed).tone(failed > 0 ? "alert" : "good", "Retry from Communications"),
                    Metric.count("Suppressed (consent)", countOf(byStatus, "SUPPRESSED"))),
                    List.of(new Breakdown("Messages by status", byStatus), new Breakdown("Messages by channel", byChannel)),
                    LocalDateTime.now(), null);
        };
    }

    /** Count-only breakdowns show no amount column. */
    private static List<BreakdownRow> strip(List<BreakdownRow> rows) {
        return rows.stream().map(r -> new BreakdownRow(r.label(), r.count(), null)).toList();
    }
}
