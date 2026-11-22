package com.nsure.reporting;

import com.nsure.reporting.ReportViews.BreakdownRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Small read helpers the report sections share: every figure is a query over the authoritative tables. */
@Component
public class ReportSql {

    private final JdbcTemplate jdbc;

    public ReportSql(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** (label, count, amount) rows; the SQL selects exactly those three columns. */
    public List<BreakdownRow> rows(String sql, Object... args) {
        return jdbc.query(sql, (rs, n) -> new BreakdownRow(String.valueOf(rs.getObject(1)), rs.getLong(2),
                rs.getObject(3) == null ? null : decimal(rs.getObject(3))), args);
    }

    public long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    public BigDecimal amount(String sql, Object... args) {
        return decimal(jdbc.queryForObject(sql, Object.class, args));
    }

    /**
     * AT-SH13-01: one key per person, however often they appear — the normalised full name and date of birth (the
     * only identity every insured life carries, individual and group alike). Demo default until the insurer agrees an
     * identity rule (D16).
     */
    public Set<String> persons(String sql, Object... args) {
        Set<String> out = new HashSet<>();
        jdbc.query(sql, rs -> {
            String name = rs.getString(1);
            Object dob = rs.getObject(2);
            if (name != null) {
                out.add(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "") + "|"
                        + (dob == null ? "?" : dob.toString()));
            }
        }, args);
        return out;
    }

    public static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }

    /** numerator ÷ denominator as a percentage to one decimal, or null when there is no denominator. */
    public static BigDecimal percent(long numerator, long denominator) {
        return denominator == 0 ? null
                : BigDecimal.valueOf(numerator * 100.0 / denominator).setScale(1, RoundingMode.HALF_UP);
    }

    public static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        return denominator == null || denominator.signum() == 0 ? null
                : numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 1, RoundingMode.HALF_UP);
    }

    public static long countOf(List<BreakdownRow> rows, String label) {
        return rows.stream().filter(r -> r.label().equals(label)).mapToLong(BreakdownRow::count).sum();
    }

    public static BigDecimal amountOf(List<BreakdownRow> rows, String label) {
        return rows.stream().filter(r -> r.label().equals(label)).map(r -> r.amount() == null ? BigDecimal.ZERO
                : r.amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Count-only breakdowns show no amount column. */
    public static List<BreakdownRow> strip(List<BreakdownRow> rows) {
        return rows.stream().map(r -> new BreakdownRow(r.label(), r.count(), null)).toList();
    }

    public static LocalDate today() {
        return LocalDate.now();
    }
}
