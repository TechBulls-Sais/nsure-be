package com.nsure.reporting;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class ReportViews {

    private ReportViews() {
    }

    /**
     * unit: COUNT, BWP or PERCENT. tone: good, watch or alert, for the dashboard colour. {@code key} names the metric's
     * entry in the metric dictionary (NS-SH13-003); {@code drill} the drill-through to its source rows (NS-SH13-005).
     * {@code definitionStatus} is the dictionary entry's status (APPROVED, PENDING_APPROVAL), filled in by the service.
     */
    public record Metric(String label, BigDecimal value, String unit, String tone, String note, String key,
            String drill, String definitionStatus) {

        public static Metric count(String label, long value) {
            return new Metric(label, BigDecimal.valueOf(value), "COUNT", null, null, null, null, null);
        }

        public static Metric bwp(String label, BigDecimal value) {
            return new Metric(label, value == null ? BigDecimal.ZERO : value, "BWP", null, null, null, null, null);
        }

        public static Metric percent(String label, BigDecimal value) {
            return new Metric(label, value, "PERCENT", null, null, null, null, null);
        }

        public Metric tone(String tone, String note) {
            return new Metric(label, value, unit, tone, note, key, drill, definitionStatus);
        }

        /** Links the figure to its dictionary entry and its drill-through. */
        public Metric defined(String key, String drill) {
            return new Metric(label, value, unit, tone, note, key, drill, definitionStatus);
        }

        Metric status(String definitionStatus) {
            return new Metric(label, value, unit, tone, note, key, drill, definitionStatus);
        }
    }

    public record BreakdownRow(String label, long count, BigDecimal amount) {
    }

    public record Breakdown(String title, List<BreakdownRow> rows) {
    }

    /**
     * NS-SH13-004 / AT-SH13-02: an operational figure against its subledger control total, the difference and every
     * item that explains it (unposted, failed or excluded).
     */
    public record ReconLine(String measure, BigDecimal operational, BigDecimal posted, BigDecimal difference,
            String status, String basis, List<ReconItem> items) {
    }

    public record ReconItem(String eventType, String sourceRef, BigDecimal amount, String state, String reason) {
    }

    /**
     * domain: INDIVIDUAL, GROUP or SHARED, so Individual and Group business are reported separately. {@code dimensions}
     * says which filters the section honours (PERIOD, PRODUCT, CHANNEL, SCHEME).
     */
    public record Section(String key, String title, String domain, int order, List<Metric> metrics,
            List<Breakdown> breakdowns, LocalDateTime asOf, String error, List<String> dimensions,
            List<ReconLine> reconciliation) {

        public Section(String key, String title, String domain, int order, List<Metric> metrics,
                List<Breakdown> breakdowns, LocalDateTime asOf, String error) {
            this(key, title, domain, order, metrics, breakdowns, asOf, error, List.of(), List.of());
        }
    }

    public record ManagementReport(List<Section> sections, LocalDateTime asOf, String freshness, List<String> filters,
            Map<String, List<Map<String, String>>> dimensionValues) {
    }
}
