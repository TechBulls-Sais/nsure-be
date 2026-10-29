package com.nsure.reporting;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class ReportViews {

    private ReportViews() {
    }

    /** unit: COUNT, BWP or PERCENT. tone: good, watch or alert, for the dashboard colour. */
    public record Metric(String label, BigDecimal value, String unit, String tone, String note) {

        public static Metric count(String label, long value) {
            return new Metric(label, BigDecimal.valueOf(value), "COUNT", null, null);
        }

        public static Metric bwp(String label, BigDecimal value) {
            return new Metric(label, value == null ? BigDecimal.ZERO : value, "BWP", null, null);
        }

        public Metric tone(String tone, String note) {
            return new Metric(label, value, unit, tone, note);
        }
    }

    public record BreakdownRow(String label, long count, BigDecimal amount) {
    }

    public record Breakdown(String title, List<BreakdownRow> rows) {
    }

    /** domain: INDIVIDUAL, GROUP or SHARED, so Individual and Group business are reported separately. */
    public record Section(String key, String title, String domain, int order, List<Metric> metrics,
            List<Breakdown> breakdowns, LocalDateTime asOf, String error) {
    }

    public record ManagementReport(List<Section> sections, LocalDateTime asOf, String freshness) {
    }
}
