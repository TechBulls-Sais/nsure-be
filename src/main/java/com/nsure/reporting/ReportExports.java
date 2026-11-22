package com.nsure.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.InvalidFieldException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Controlled exports (NS-SH13-005, AT-SH13-03). Every export is logged with who, what, the filters, the row count and
 * whether it was masked; exports holding unmasked personal data or more than {@code nsure.reporting.large-export-rows}
 * rows are also recorded as audit evidence. A partner's export is always cut to the partner's own portfolio, whichever
 * template it asks for.
 */
@Service
public class ReportExports {

    /** The partner templates: the broader one exists to prove it never widens what a partner receives. */
    public static final List<String> PARTNER_TEMPLATES = List.of("MY_BOOK", "ALL_POLICIES");

    private final JdbcTemplate jdbc;
    private final PlatformSecurityContext security;
    private final ReportDrill drill;
    private final ReportingService reporting;
    private final EvidenceChain audit;
    private final int largeRows;

    public ReportExports(JdbcTemplate jdbc, PlatformSecurityContext security, ReportDrill drill,
            ReportingService reporting, EvidenceChain audit,
            @Value("${nsure.reporting.large-export-rows:1000}") int largeRows) {
        this.jdbc = jdbc;
        this.security = security;
        this.drill = drill;
        this.reporting = reporting;
        this.audit = audit;
        this.largeRows = largeRows;
    }

    /**
     * {kind: DRILL | SECTION, key, from?, to?, product?, intermediary?, scheme?}: the rows for a CSV file. A drill
     * export applies the drill's own permission and masking; a section export holds only aggregates.
     */
    @Transactional
    public Map<String, Object> export(JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("READ_REPORT");
        String kind = text(body, "kind") == null ? "DRILL" : text(body, "kind").toUpperCase(Locale.ROOT);
        String key = text(body, "key");
        if (key == null) {
            throw new InvalidFieldException("key", "Say which figure or section to export.");
        }
        Map<String, String> params = new HashMap<>();
        for (String f : List.of("from", "to", "product", "intermediary", "scheme")) {
            params.put(f, text(body, f));
        }
        ReportFilter filter = ReportFilter.of(params);
        List<Map<String, Object>> rows;
        List<Map<String, String>> columns;
        boolean masked;
        boolean personal;
        String title;
        if ("SECTION".equals(kind)) {
            ReportViews.Section section = reporting.section(key, filter);
            title = section.title();
            rows = new ArrayList<>();
            for (ReportViews.Metric m : section.metrics()) {
                rows.add(row("Figure", m.label(), null, m.value(), m.unit(), m.definitionStatus()));
            }
            for (ReportViews.Breakdown b : section.breakdowns()) {
                for (ReportViews.BreakdownRow r : b.rows()) {
                    rows.add(row(b.title(), r.label(), r.count(), r.amount(), r.amount() == null ? "COUNT" : "BWP",
                            null));
                }
            }
            columns = List.of(col("group", "Group"), col("label", "Item"), col("count", "Count"), col("value", "Value"),
                    col("unit", "Unit"), col("definition", "Definition status"));
            masked = false;
            personal = false;
        } else if ("DRILL".equals(kind)) {
            ReportDrill.Result r = drill.run(user, key, filter, Integer.MAX_VALUE);
            title = r.title();
            rows = r.rows();
            columns = r.columns();
            masked = r.masked();
            personal = !masked && r.columns().stream().anyMatch(c -> List.of("customer", "date_of_birth")
                    .contains(c.get("key")));
        } else {
            throw new InvalidFieldException("kind", "kind must be DRILL or SECTION.");
        }
        String ref = log(kind, title, "Staff report: " + key, String.join("; ", filter.describe()), rows.size(), masked,
                personal, user);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ref", ref);
        out.put("title", title);
        out.put("columns", columns);
        out.put("rows", rows);
        out.put("recordCount", rows.size());
        out.put("masked", masked);
        out.put("sensitive", personal);
        out.put("large", rows.size() > largeRows);
        return out;
    }

    /**
     * AT-SH13-03: a partner's portfolio export. Whatever template is asked for (MY_BOOK or the broader ALL_POLICIES),
     * the rows are those of the partner the login is tied to: an agent's assigned and sold policies, an employer's
     * scheme members, a lender's loans.
     */
    @Transactional
    public Map<String, Object> partnerPortfolio(String template) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("USE_PARTNER_PORTAL");
        String requested = template == null ? "MY_BOOK" : template.toUpperCase(Locale.ROOT);
        if (!PARTNER_TEMPLATES.contains(requested)) {
            throw new InvalidFieldException("template", "template must be one of " + PARTNER_TEMPLATES + ".");
        }
        List<Map<String, Object>> account = jdbc.queryForList("select portal_type, scope_ref from portal_account"
                + " where username = ?", user.getUsername());
        if (account.isEmpty()) {
            throw new NoAuthorizationException("This login is not linked to an agent, employer or lender.");
        }
        String type = String.valueOf(value(account.get(0), "portal_type"));
        String scope = String.valueOf(value(account.get(0), "scope_ref"));
        List<Map<String, Object>> rows = switch (type) {
            case "AGENT" -> jdbc.queryForList("select p.record_ref policy, p.customer_name customer, p.product, p.status,"
                    + " p.amount sum_assured, p.premium from ins_policy p where p.record_ref in (select a.policy_ref from"
                    + " dist_policy_assignment a join dist_intermediary i on i.id = a.intermediary_id where"
                    + " i.record_ref = ? and a.ended_on is null) or p.application_ref in (select ap.record_ref from"
                    + " ins_application ap where ap.intermediary_code = ?) order by p.record_ref", scope, scope);
            case "EMPLOYER" -> jdbc.queryForList("select m.member_key member, m.first_name, m.last_name,"
                    + " m.member_status status, m.accepted_cover, m.pending_cover from grp_member m join ins_scheme s on"
                    + " s.id = m.scheme_id where s.record_ref = ? order by m.member_key", scope);
            default -> jdbc.queryForList("select record_ref loan, lender_loan_id, borrower_name, balance, status,"
                    + " policy_ref from crd_loan where lender_ref = ? order by record_ref", scope);
        };
        List<Map<String, Object>> clean = rows.stream().map(ReportExports::lowerKeys).toList();
        String scopeText = switch (type) {
            case "AGENT" -> "Intermediary " + scope + " only";
            case "EMPLOYER" -> "Scheme " + scope + " only";
            default -> "Lender " + scope + " only";
        };
        String ref = log("PARTNER", "Partner portfolio (" + requested + ")", scopeText, "template " + requested,
                clean.size(), false, true, user);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ref", ref);
        out.put("requestedTemplate", requested);
        out.put("scope", scopeText);
        out.put("partnerType", type);
        out.put("rows", clean);
        out.put("recordCount", clean.size());
        return out;
    }

    /** The export log, newest first. READ_REPORT. */
    public List<Map<String, Object>> log() {
        security.authenticatedUser().validateHasPermissionTo("READ_REPORT");
        return jdbc.query("select * from rpt_export_log order by id desc", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", rs.getString("record_ref"));
            m.put("kind", rs.getString("export_kind"));
            m.put("subject", rs.getString("subject"));
            m.put("scope", rs.getString("scope"));
            m.put("filters", rs.getString("filters"));
            m.put("recordCount", rs.getInt("record_count"));
            m.put("masked", rs.getBoolean("masked"));
            m.put("sensitive", rs.getBoolean("sensitive"));
            m.put("large", rs.getBoolean("large"));
            m.put("exportedBy", rs.getString("exported_by"));
            m.put("exportedAt", rs.getTimestamp("exported_at").toLocalDateTime());
            return m;
        });
    }

    private String log(String kind, String subject, String scope, String filters, int count, boolean masked,
            boolean sensitive, AppUser user) {
        Integer n = jdbc.queryForObject("select count(*) from rpt_export_log", Integer.class);
        String ref = String.format("RX-%06d", (n == null ? 0 : n) + 1);
        boolean large = count > largeRows;
        jdbc.update("insert into rpt_export_log (record_ref, export_kind, subject, scope, filters, record_count, masked,"
                + " sensitive, large, exported_by, exported_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", ref, kind,
                clip(subject, 100), clip(scope, 200), clip(filters, 400), count, masked, sensitive, large,
                user.getUsername(), LocalDateTime.now());
        if (sensitive || large) {
            audit.record(user.getUsername(), "Report exported" + (sensitive ? " (personal data)" : "")
                    + (large ? " (large)" : ""), "Export", ref, subject + ": " + count + " rows; " + scope);
        }
        return ref;
    }

    private static Map<String, Object> row(String group, String label, Long count, Object value, String unit,
            String definition) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("group", group);
        m.put("label", label);
        m.put("count", count);
        m.put("value", value);
        m.put("unit", unit);
        m.put("definition", definition);
        return m;
    }

    private static Map<String, String> col(String key, String label) {
        return Map.of("key", key, "label", label);
    }

    private static Map<String, Object> lowerKeys(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, Object>> it = row.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Object> e = it.next();
            out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        return out;
    }

    private static Object value(Map<String, Object> row, String column) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(column)) {
                return e.getValue();
            }
        }
        throw new NotFoundException(column);
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static String text(JsonNode body, String field) {
        return body != null && body.hasNonNull(field) && !body.get(field).asText().isBlank()
                ? body.get(field).asText().trim() : null;
    }
}
