package com.nsure.reporting;

import com.nsure.finance.FinanceService;
import com.nsure.finance.FinanceViews;
import com.nsure.integration.IntegrationService;
import com.nsure.integration.IntegrationViews;
import com.nsure.reporting.ReportViews.Breakdown;
import com.nsure.reporting.ReportViews.BreakdownRow;
import com.nsure.reporting.ReportViews.Metric;
import com.nsure.reporting.ReportViews.Section;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Sections for Individual business, the subledger and integration health. */
@Configuration
public class CoreReportSections {

    @Bean
    ReportSection individualSection(EntityManager em) {
        return () -> {
            List<BreakdownRow> policies = rows(em, "select p.status, count(p), sum(p.premium) from InsurancePolicy p"
                    + " group by p.status order by p.status");
            List<BreakdownRow> claims = rows(em, "select c.status, count(c), sum(c.payoutAmount) from InsuranceClaim c"
                    + " group by c.status order by c.status");
            List<BreakdownRow> products = rows(em, "select p.product, count(p), sum(p.premium) from InsurancePolicy p"
                    + " where p.status = 'Active' group by p.product order by p.product");
            long active = countOf(policies, "Active");
            long lapsed = countOf(policies, "Lapsed");
            BigDecimal inForce = amountOf(policies, "Active");
            long openClaims = claims.stream()
                    .filter(r -> List.of("Initiated", "Under Review", "Recommended", "Approved").contains(r.label()))
                    .mapToLong(BreakdownRow::count).sum();
            long applications = em.createQuery("select count(a) from InsuranceApplication a", Long.class)
                    .getSingleResult();
            List<Metric> metrics = new ArrayList<>();
            metrics.add(Metric.count("Policies in force", active));
            metrics.add(Metric.bwp("Monthly premium in force", inForce));
            metrics.add(Metric.count("Lapsed policies", lapsed).tone(lapsed > 0 ? "watch" : "good", null));
            metrics.add(Metric.count("Applications", applications));
            metrics.add(Metric.count("Open claims", openClaims).tone(openClaims > 0 ? "watch" : "good", null));
            metrics.add(Metric.bwp("Claims paid", amountOf(claims, "Paid")));
            return new Section("individual", "Individual life", "INDIVIDUAL", 10, metrics,
                    List.of(new Breakdown("Policies by status", policies), new Breakdown("Claims by status", claims),
                            new Breakdown("In-force premium by product", products)),
                    LocalDateTime.now(), null);
        };
    }

    @Bean
    ReportSection financeSection(FinanceService finance) {
        return () -> {
            FinanceViews.FinanceSummary s = finance.summary();
            List<Metric> metrics = List.of(
                    Metric.bwp("Premium income posted", s.premiumIncome()),
                    Metric.bwp("Claims paid posted", s.claimsPaid()),
                    Metric.bwp("Commission expense", s.commissionExpense()),
                    Metric.bwp("Reinsurance premium ceded", s.reinsuranceCeded()),
                    Metric.bwp("Suspense balance", s.suspense())
                            .tone(s.suspense().signum() > 0 ? "alert" : "good", "Cash received but not applied"),
                    Metric.count("Unposted events", s.openExceptions())
                            .tone(s.openExceptions() > 0 ? "alert" : "good", "Financial exceptions"),
                    Metric.count("Journals awaiting GL export", s.unexportedJournals())
                            .tone(s.unexportedJournals() > 0 ? "watch" : "good", null),
                    Metric.count("GL batches awaiting acknowledgement", s.submittedBatches()));
            return new Section("finance", "Insurance subledger", "SHARED", 30, metrics, List.of(), s.asOf(), null);
        };
    }

    @Bean
    ReportSection integrationSection(IntegrationService integration) {
        return () -> {
            IntegrationViews.DashboardView d = integration.summary();
            long pending = d.deliveriesByStatus().getOrDefault("PENDING", 0L);
            List<Metric> metrics = List.of(
                    Metric.count("Business events", d.events()),
                    Metric.count("Deliveries pending", pending).tone(pending > 0 ? "watch" : "good",
                            d.oldestPending() == null ? null : "Oldest from " + d.oldestPending().withNano(0)),
                    Metric.count("Deliveries failed", d.failed()).tone(d.failed() > 0 ? "alert" : "good",
                            "Replay from the integration monitor"),
                    Metric.count("Payment notifications in suspense", d.inboundByStatus().getOrDefault("SUSPENSE", 0L)));
            List<BreakdownRow> inbound = d.inboundByStatus().entrySet().stream()
                    .map(e -> new BreakdownRow(e.getKey(), e.getValue(), null)).toList();
            return new Section("integration", "Integration health", "SHARED", 90, metrics,
                    List.of(new Breakdown("Inbound messages", inbound)), d.asOf(), null);
        };
    }

    static List<BreakdownRow> rows(EntityManager em, String jpql) {
        return em.createQuery(jpql, Object[].class).getResultList().stream()
                .map(row -> new BreakdownRow(String.valueOf(row[0]), ((Number) row[1]).longValue(), decimal(row[2])))
                .toList();
    }

    static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }

    static long countOf(List<BreakdownRow> rows, String label) {
        return rows.stream().filter(r -> r.label().equals(label)).mapToLong(BreakdownRow::count).sum();
    }

    static BigDecimal amountOf(List<BreakdownRow> rows, String label) {
        return rows.stream().filter(r -> r.label().equals(label)).map(BreakdownRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
