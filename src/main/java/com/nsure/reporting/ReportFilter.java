package com.nsure.reporting;

import com.nsure.common.exception.InvalidFieldException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The authorised dimensions of a report (NS-SH13-002): a period for flow measures (business written, premiums
 * collected, claims registered or paid in it), a product, a channel (an intermediary's reference, or DIRECT for
 * business with no intermediary) and a group scheme. Stock measures (in force, open) are as at the time shown.
 * Each method returns a SQL condition with its parameters, written for both H2 and MySQL.
 */
public record ReportFilter(LocalDate from, LocalDate to, String product, String intermediary, String scheme) {

    public static final String DIRECT = "DIRECT";

    public static ReportFilter none() {
        return new ReportFilter(null, null, null, null, null);
    }

    public static ReportFilter of(Map<String, String> params) {
        LocalDate from = date(params, "from");
        LocalDate to = date(params, "to");
        if (from != null && to != null && to.isBefore(from)) {
            throw new InvalidFieldException("to", "The period ends before it starts.");
        }
        return new ReportFilter(from, to, blank(params.get("product")), blank(params.get("intermediary")),
                blank(params.get("scheme")));
    }

    public boolean hasPeriod() {
        return from != null || to != null;
    }

    /** Human-readable list of what is applied, for the report header and export logs. */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        if (hasPeriod()) {
            out.add("Period " + (from == null ? "start" : from) + " to " + (to == null ? "today" : to));
        }
        if (product != null) {
            out.add("Product " + product);
        }
        if (intermediary != null) {
            out.add(DIRECT.equals(intermediary) ? "Direct business" : "Intermediary " + intermediary);
        }
        if (scheme != null) {
            out.add("Scheme " + scheme);
        }
        return out;
    }

    /** A condition plus its parameters. */
    public record Sql(String where, List<Object> args) {

        public Object[] params() {
            return args.toArray();
        }
    }

    /** Individual policies (alias {@code p}); {@code periodColumn} null for stock measures. */
    public Sql policies(String periodColumn) {
        StringBuilder w = new StringBuilder(" 1 = 1");
        List<Object> a = new ArrayList<>();
        if (product != null) {
            w.append(" and p.product = ?");
            a.add(product);
        }
        if (intermediary != null) {
            if (DIRECT.equals(intermediary)) {
                w.append(" and not exists (select 1 from dist_policy_assignment pa where pa.policy_ref = p.record_ref"
                        + " and pa.ended_on is null) and not exists (select 1 from ins_application pap where"
                        + " pap.record_ref = p.application_ref and pap.intermediary_code is not null)");
            } else {
                w.append(" and (exists (select 1 from dist_policy_assignment pa join dist_intermediary pi on"
                        + " pi.id = pa.intermediary_id where pa.policy_ref = p.record_ref and pa.ended_on is null and"
                        + " pi.record_ref = ?) or exists (select 1 from ins_application pap where pap.record_ref ="
                        + " p.application_ref and pap.intermediary_code = ?))");
                a.add(intermediary);
                a.add(intermediary);
            }
        }
        period(w, a, periodColumn);
        return new Sql(w.toString(), a);
    }

    /** Rows that name a policy in {@code policyColumn}: the policy dimensions apply through that policy. */
    public Sql viaPolicy(String policyColumn, String periodColumn) {
        StringBuilder w = new StringBuilder(" 1 = 1");
        List<Object> a = new ArrayList<>();
        if (product != null || intermediary != null) {
            Sql p = new ReportFilter(null, null, product, intermediary, null).policies(null);
            w.append(" and exists (select 1 from ins_policy p where p.record_ref = ").append(policyColumn)
                    .append(" and").append(p.where()).append(")");
            a.addAll(p.args());
        }
        period(w, a, periodColumn);
        return new Sql(w.toString(), a);
    }

    /** Applications (alias {@code a}). */
    public Sql applications(String periodColumn) {
        StringBuilder w = new StringBuilder(" 1 = 1");
        List<Object> a = new ArrayList<>();
        if (product != null) {
            w.append(" and a.product = ?");
            a.add(product);
        }
        if (intermediary != null) {
            if (DIRECT.equals(intermediary)) {
                w.append(" and a.intermediary_code is null");
            } else {
                w.append(" and a.intermediary_code = ?");
                a.add(intermediary);
            }
        }
        period(w, a, periodColumn);
        return new Sql(w.toString(), a);
    }

    /** Group members (alias {@code m}, scheme alias {@code s}). Product and channel do not apply to group. */
    public Sql members(String periodColumn) {
        StringBuilder w = new StringBuilder(" 1 = 1");
        List<Object> a = new ArrayList<>();
        if (scheme != null) {
            w.append(" and s.record_ref = ?");
            a.add(scheme);
        }
        period(w, a, periodColumn);
        return new Sql(w.toString(), a);
    }

    /** Only the period (for rows with no product, channel or scheme). */
    public Sql period(String periodColumn) {
        StringBuilder w = new StringBuilder(" 1 = 1");
        List<Object> a = new ArrayList<>();
        period(w, a, periodColumn);
        return new Sql(w.toString(), a);
    }

    private void period(StringBuilder w, List<Object> a, String column) {
        if (column == null) {
            return;
        }
        if (from != null) {
            w.append(" and ").append(column).append(" >= ?");
            a.add(from.atStartOfDay());
        }
        if (to != null) {
            w.append(" and ").append(column).append(" < ?");
            a.add(to.plusDays(1).atStartOfDay());
        }
    }

    private static LocalDate date(Map<String, String> params, String field) {
        String value = blank(params.get(field));
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new InvalidFieldException(field, field + " must be a date (yyyy-mm-dd).");
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
