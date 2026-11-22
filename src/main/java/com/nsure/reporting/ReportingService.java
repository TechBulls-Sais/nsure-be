package com.nsure.reporting;

import com.nsure.common.exception.NotFoundException;
import com.nsure.security.service.PlatformSecurityContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The management dashboard (SH13): every section read from authoritative tables at request time, stamped with its
 * as-of time (NS-DAT-005), filtered by the authorised dimensions (NS-SH13-002) and with each figure linked to its
 * metric dictionary entry and approval status (NS-SH13-003). A section that fails reports its error instead of hiding
 * the rest of the dashboard.
 */
@Service
public class ReportingService {

    private static final Logger log = LoggerFactory.getLogger(ReportingService.class);

    private final PlatformSecurityContext security;
    private final ObjectProvider<ReportSection> sections;
    private final MetricDictionary dictionary;
    private final JdbcTemplate jdbc;

    public ReportingService(PlatformSecurityContext security, ObjectProvider<ReportSection> sections,
            MetricDictionary dictionary, JdbcTemplate jdbc) {
        this.security = security;
        this.sections = sections;
        this.dictionary = dictionary;
        this.jdbc = jdbc;
    }

    public ReportViews.ManagementReport management() {
        return management(Map.of());
    }

    // Not one transaction: each section reads on its own, so one failing query cannot roll back the others.
    public ReportViews.ManagementReport management(Map<String, String> params) {
        security.authenticatedUser().validateHasPermissionTo("READ_REPORT");
        ReportFilter filter = ReportFilter.of(params);
        List<ReportViews.Section> built = build(filter);
        Map<String, List<Map<String, String>>> dimensions = new LinkedHashMap<>();
        dimensions.put("products", jdbc.query("select distinct product from ins_policy union select distinct product"
                + " from ins_application order by 1", (rs, n) -> Map.of("value", rs.getString(1), "label",
                rs.getString(1))));
        List<Map<String, String>> channels = new ArrayList<>();
        channels.add(Map.of("value", ReportFilter.DIRECT, "label", "Direct (no intermediary)"));
        channels.addAll(jdbc.query("select record_ref, name from dist_intermediary order by name", (rs, n) ->
                Map.of("value", rs.getString(1), "label", rs.getString(2) + " · " + rs.getString(1))));
        dimensions.put("intermediaries", channels);
        dimensions.put("schemes", jdbc.query("select record_ref, name from ins_scheme order by name", (rs, n) ->
                Map.of("value", rs.getString(1), "label", rs.getString(2) + " · " + rs.getString(1))));
        return new ReportViews.ManagementReport(built, LocalDateTime.now(),
                "Live: read from the transaction tables at the time shown. Not an approved financial statement.",
                filter.describe(), dimensions);
    }

    /** One section, for an export. READ_REPORT is checked by the caller. */
    ReportViews.Section section(String key, ReportFilter filter) {
        return build(filter).stream().filter(s -> s.key().equals(key)).findFirst()
                .orElseThrow(() -> new NotFoundException("Report section " + key + " not found"));
    }

    private List<ReportViews.Section> build(ReportFilter filter) {
        Map<String, String> statuses = dictionary.statuses();
        List<ReportViews.Section> built = new ArrayList<>();
        for (ReportSection section : sections.orderedStream().toList()) {
            try {
                ReportViews.Section s = section.build(filter);
                built.add(new ReportViews.Section(s.key(), s.title(), s.domain(), s.order(), s.metrics().stream()
                        .map(m -> m.key() == null ? m : m.status(statuses.getOrDefault(m.key(), "UNDEFINED")))
                        .toList(), s.breakdowns(), s.asOf(), s.error(), s.dimensions(), s.reconciliation()));
            } catch (RuntimeException ex) {
                log.warn("Report section {} failed", section.getClass().getSimpleName(), ex);
                built.add(new ReportViews.Section(section.getClass().getSimpleName(), section.getClass().getSimpleName(),
                        "SHARED", 999, List.of(), List.of(), LocalDateTime.now(), "This section could not be loaded."));
            }
        }
        built.sort(Comparator.comparingInt(ReportViews.Section::order));
        return built;
    }
}
