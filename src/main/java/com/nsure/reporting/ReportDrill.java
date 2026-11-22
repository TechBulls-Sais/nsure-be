package com.nsure.reporting;

import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * Drill-through from a dashboard figure to its source rows (NS-SH13-005). Each drill names the permission its rows
 * need — the same code the module's own list checks — so a report reader sees only what they could open anyway.
 * Customer names and dates of birth are masked for anyone without READ_CUSTOMER. Rows are capped at 500.
 */
@Service
public class ReportDrill {

    static final int LIMIT = 500;

    /** One drill: title, permission, columns (key, label) and the query for a filter. */
    record Spec(String title, String permission, String metricKey, List<String[]> columns, List<String> personal,
            Function<ReportFilter, ReportFilter.Sql> where, String sql) {
    }

    private final ReportSql sql;
    private final PlatformSecurityContext security;
    private final MetricDictionary dictionary;
    private final Map<String, Spec> specs = new LinkedHashMap<>();

    public ReportDrill(ReportSql sql, PlatformSecurityContext security, MetricDictionary dictionary) {
        this.sql = sql;
        this.security = security;
        this.dictionary = dictionary;
        String policyCols = "select p.record_ref policy, p.customer_name customer, p.product, p.status, p.cover_start_date"
                + " cover_start, p.amount sum_assured, p.premium from ins_policy p where";
        List<String[]> policyColumns = List.of(new String[] {"policy", "Policy"}, new String[] {"customer", "Customer"},
                new String[] {"product", "Product"}, new String[] {"status", "Status"},
                new String[] {"cover_start", "Cover start"}, new String[] {"sum_assured", "Sum assured"},
                new String[] {"premium", "Premium"});
        add("policies.inforce", new Spec("Policies in force", "READ_POLICY", "active_policies", policyColumns,
                List.of("customer"), f -> f.policies(null), policyCols + " p.status = 'Active' and"));
        add("policies.issued", new Spec("New business issued", "READ_POLICY", "new_business", policyColumns,
                List.of("customer"), f -> f.policies("p.cover_start_date"), policyCols + " p.cover_start_date is not null and"));
        add("policies.lapsed", new Spec("Lapsed or without cover", "READ_POLICY", "lapsed_policies", policyColumns,
                List.of("customer"), f -> f.policies(null),
                policyCols + " p.status in ('Lapsed', 'Active - No Cover') and"));
        add("policies.grace", new Spec("Policies in grace", "READ_POLICY", null, policyColumns, List.of("customer"),
                f -> f.policies(null), policyCols + " p.status = 'Active' and p.next_premium_due < CURRENT_DATE and"
                        + " p.grace_period_end >= CURRENT_DATE and"));
        String claimCols = "select c.record_ref claim, c.policy_ref policy, c.customer_name customer, c.product,"
                + " c.claim_type, c.status, c.amount claimed, c.payout_amount paid, c.lodged_at, c.paid_at from ins_claim c"
                + " where";
        List<String[]> claimColumns = List.of(new String[] {"claim", "Claim"}, new String[] {"policy", "Policy"},
                new String[] {"customer", "Customer"}, new String[] {"product", "Product"},
                new String[] {"claim_type", "Type"}, new String[] {"status", "Status"},
                new String[] {"claimed", "Claimed"}, new String[] {"paid", "Paid"},
                new String[] {"lodged_at", "Lodged"}, new String[] {"paid_at", "Paid on"});
        add("claims.open", new Spec("Open claims", "READ_CLAIM", "open_claims", claimColumns, List.of("customer"),
                f -> f.viaPolicy("c.policy_ref", null), claimCols + " c.status in ('Initiated', 'Under Review',"
                        + " 'Recommended', 'Approved', 'Payout Sent', 'Payout Failed') and"));
        add("claims.paid", new Spec("Claims paid", "READ_CLAIM", "claims_paid", claimColumns, List.of("customer"),
                f -> f.viaPolicy("c.policy_ref", "c.paid_at"), claimCols + " c.status = 'Paid' and"));
        List<String[]> appColumns = List.of(new String[] {"application", "Application"},
                new String[] {"customer", "Customer"}, new String[] {"product", "Product"},
                new String[] {"status", "Status"}, new String[] {"channel", "Channel"},
                new String[] {"intermediary", "Intermediary"}, new String[] {"submitted_at", "Submitted"});
        String appCols = "select a.record_ref application, a.customer_name customer, a.product, a.status, a.channel,"
                + " a.intermediary_code intermediary, a.submitted_at from ins_application a where";
        add("nb.submitted", new Spec("Applications submitted", "READ_APPLICATION", "applications_submitted", appColumns,
                List.of("customer"), f -> f.applications(f.hasPeriod() ? "a.submitted_at" : null),
                appCols + " a.submitted_at is not null and"));
        add("nb.issued", new Spec("Policies issued from applications", "READ_APPLICATION", "new_business", appColumns,
                List.of("customer"), f -> f.applications(f.hasPeriod() ? "a.submitted_at" : null),
                appCols + " exists (select 1 from ins_policy p where p.application_ref = a.record_ref) and"));
        add("nb.quotes", new Spec("Quotes", "READ_APPLICATION", "quotes", List.of(new String[] {"quote", "Quote"},
                new String[] {"version", "Version"}, new String[] {"customer", "Customer"},
                new String[] {"product", "Product"}, new String[] {"premium", "Premium"}, new String[] {"status", "Status"},
                new String[] {"created_at", "Created"}), List.of("customer"), f -> {
                    ReportFilter.Sql p = f.period("q.created_at");
                    if (f.product() == null) {
                        return p;
                    }
                    List<Object> args = new ArrayList<>(List.of(f.product()));
                    args.addAll(p.args());
                    return new ReportFilter.Sql(" q.product = ? and" + p.where(), args);
                }, "select q.record_ref quote, q.version_no version, q.customer_name customer, q.product, q.premium,"
                        + " q.status, q.created_at from nb_quote q where"));
        add("uw.open", new Spec("Open underwriting cases", "READ_UNDERWRITING", "uw_queue", List.of(
                new String[] {"case_ref", "Case"}, new String[] {"application", "Application"},
                new String[] {"customer", "Customer"}, new String[] {"product", "Product"},
                new String[] {"status", "Status"}, new String[] {"assigned_to", "Assigned to"},
                new String[] {"created_at", "Opened"}), List.of("customer"), f -> ReportFilter.none().period(null),
                "select u.record_ref case_ref, u.application_ref application, u.customer_name customer, u.product,"
                        + " u.status, u.assigned_to, u.created_at from uw_case u where u.status not in ('ACCEPTED',"
                        + " 'DECLINED', 'POSTPONED', 'WITHDRAWN') and"));
        add("uw.evidence", new Spec("Evidence outstanding", "READ_UNDERWRITING", "evidence_outstanding", List.of(
                new String[] {"case_ref", "Case"}, new String[] {"requirement", "Requirement"},
                new String[] {"due_date", "Due"}, new String[] {"requested_by", "Requested by"},
                new String[] {"requested_at", "Requested"}), List.of(), f -> ReportFilter.none().period(null),
                "select e.case_ref, e.requirement, e.due_date, e.requested_by, e.requested_at from uw_evidence e where"
                        + " e.status = 'REQUESTED' and"));
        List<String[]> instColumns = List.of(new String[] {"instalment", "Instalment"}, new String[] {"policy", "Policy"},
                new String[] {"due_date", "Due"}, new String[] {"billed", "Billed"}, new String[] {"status", "Status"});
        String instCols = "select i.record_ref instalment, i.policy_ref policy, i.due_date, i.billed_amount billed,"
                + " i.status from bil_instalment i where";
        add("collections.due", new Spec("Premium due", "READ_POLICY", "premium_due", instColumns, List.of(),
                f -> f.viaPolicy("i.policy_ref", f.hasPeriod() ? "i.due_date" : null),
                instCols + " i.status <> 'CANCELLED' and"));
        add("collections.overdue", new Spec("Arrears", "READ_POLICY", "arrears", instColumns, List.of(),
                f -> f.viaPolicy("i.policy_ref", null),
                instCols + " i.status in ('DUE', 'PART_PAID') and i.due_date < CURRENT_DATE and"));
        add("collections.collected", new Spec("Premium collected", "READ_POLICY", "premium_collected", List.of(
                new String[] {"receipt", "Receipt"}, new String[] {"policy", "Policy"},
                new String[] {"period_start", "Period"}, new String[] {"amount", "Amount"},
                new String[] {"method", "Method"}, new String[] {"paid_at", "Paid"}), List.of(),
                f -> f.viaPolicy("pp.policy_ref", "pp.paid_at"), "select pp.receipt_ref receipt, pp.policy_ref policy,"
                        + " pp.period_start, pp.amount, pp.payment_method method, pp.paid_at from premium_payment pp"
                        + " where pp.status = 'SUCCESSFUL' and"));
        List<String[]> memberColumns = List.of(new String[] {"scheme", "Scheme"}, new String[] {"member", "Member"},
                new String[] {"customer", "Name"}, new String[] {"date_of_birth", "Date of birth"},
                new String[] {"status", "Status"}, new String[] {"accepted", "Accepted cover"},
                new String[] {"pending", "Pending cover"});
        String memberCols = "select s.record_ref scheme, m.member_key member, concat(concat(m.first_name, ' '),"
                + " m.last_name) customer, m.date_of_birth, m.member_status status, m.accepted_cover accepted,"
                + " m.pending_cover pending from grp_member m join ins_scheme s on s.id = m.scheme_id where";
        add("group.members", new Spec("Covered member lives", "READ_SCHEME", "active_lives", memberColumns,
                List.of("customer", "date_of_birth"), f -> f.members(null),
                memberCols + " m.member_status in ('ACTIVE', 'PARTIALLY_ACCEPTED') and"));
        add("group.pending", new Spec("Members pending underwriting", "READ_SCHEME", null, memberColumns,
                List.of("customer", "date_of_birth"), f -> f.members(null),
                memberCols + " m.member_status = 'UNDERWRITING_PENDING' and"));
        add("lives.group", new Spec("Group member lives", "READ_SCHEME", "active_lives", memberColumns,
                List.of("customer", "date_of_birth"), f -> f.members(null),
                memberCols + " m.member_status in ('ACTIVE', 'PARTIALLY_ACCEPTED') and"));
        add("lives.individual", new Spec("Individual insured lives", "READ_POLICY", "active_lives", List.of(
                new String[] {"policy", "Policy"}, new String[] {"customer", "Life"},
                new String[] {"date_of_birth", "Date of birth"}, new String[] {"life_type", "Role"},
                new String[] {"cover", "Cover"}), List.of("customer", "date_of_birth"), f -> f.policies(null),
                "select p.record_ref policy, lc.full_name customer, lc.date_of_birth, lc.life_type, lc.cover_amount cover"
                        + " from ins_policy_life_cover lc join ins_policy p on p.id = lc.policy_id where p.status ="
                        + " 'Active' and lc.life_status = 'ACTIVE' and"));
        add("finance.unposted", new Spec("Unposted events", "READ_FINANCE", null, List.of(
                new String[] {"event_type", "Event"}, new String[] {"source_ref", "Reference"},
                new String[] {"amount", "Amount"}, new String[] {"reason", "Reason"},
                new String[] {"created_at", "Raised"}), List.of(), f -> ReportFilter.none().period(null),
                "select x.event_type, x.source_ref, x.amount, x.reason, x.created_at from fin_exception x where"
                        + " x.status = 'OPEN' and"));
        add("commission.unpaid", new Spec("Commission accrued, unpaid", "READ_DISTRIBUTION", null, List.of(
                new String[] {"commission", "Line"}, new String[] {"intermediary", "Intermediary"},
                new String[] {"policy", "Policy"}, new String[] {"kind", "Kind"}, new String[] {"amount", "Amount"},
                new String[] {"status", "Status"}), List.of(), f -> f.period("c.created_at"),
                "select c.record_ref commission, i.record_ref intermediary, c.policy_ref policy, c.line_kind kind,"
                        + " c.amount, c.status from dist_commission c join dist_intermediary i on i.id ="
                        + " c.intermediary_id where c.status in ('ACCRUED', 'STATEMENTED') and"));
        add("service.breached", new Spec("SLA breached", "READ_SERVICE_CASE", null, List.of(
                new String[] {"case_ref", "Case"}, new String[] {"case_type", "Type"},
                new String[] {"customer", "Customer"}, new String[] {"status", "Status"},
                new String[] {"owner", "Owner"}, new String[] {"due_at", "Due"}), List.of("customer"),
                f -> ReportFilter.none().period(null), "select c.record_ref case_ref, c.case_type, c.customer_name"
                        + " customer, c.status, c.owner, c.due_at from svc_case c where c.status in ('OPEN',"
                        + " 'IN_PROGRESS') and c.due_at < CURRENT_TIMESTAMP and"));
    }

    private void add(String key, Spec spec) {
        specs.put(key, spec);
    }

    public record Result(String key, String title, String permission, Map<String, Object> definition,
            List<Map<String, String>> columns, List<Map<String, Object>> rows, int total, boolean truncated,
            boolean masked, List<String> filters) {
    }

    /** The source rows behind a figure, permission-checked and masked. */
    public Result drill(String key, ReportFilter filter) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("READ_REPORT");
        return run(user, key, filter, LIMIT);
    }

    Result run(AppUser user, String key, ReportFilter filter, int limit) {
        Spec spec = specs.get(key);
        if (spec == null) {
            throw new NotFoundException("No drill-through " + key);
        }
        user.validateHasPermissionTo(spec.permission());
        boolean mask = !user.hasPermissionTo("READ_CUSTOMER");
        ReportFilter.Sql where = spec.where().apply(filter);
        List<Map<String, Object>> rows = new ArrayList<>();
        int[] total = {0};
        sql.jdbc().query(spec.sql() + where.where() + " order by 1", rs -> {
            total[0]++;
            if (rows.size() >= limit) {
                return;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (String[] c : spec.columns()) {
                Object value = rs.getObject(c[0]);
                if (value instanceof java.sql.Timestamp ts) {
                    value = ts.toLocalDateTime();
                } else if (value instanceof java.sql.Date d) {
                    value = d.toLocalDate();
                }
                if (mask && spec.personal().contains(c[0])) {
                    value = mask(value);
                }
                row.put(c[0], value);
            }
            rows.add(row);
        }, where.params());
        List<Map<String, String>> columns = spec.columns().stream()
                .map(c -> Map.of("key", c[0], "label", c[1])).toList();
        return new Result(key, spec.title(), spec.permission(), dictionary.current(spec.metricKey()), columns, rows,
                total[0], total[0] > rows.size(), mask && !spec.personal().isEmpty(), filter.describe());
    }

    /** "Kagiso Molefe" → "K***** M*****"; a date of birth keeps only its year. */
    static Object mask(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate d) {
            return d.getYear() + "-**-**";
        }
        StringBuilder out = new StringBuilder();
        for (String part : value.toString().trim().split("\\s+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append("*".repeat(Math.max(2, part.length() - 1)));
        }
        return out.toString();
    }

    public boolean exists(String key) {
        return specs.containsKey(key);
    }
}
