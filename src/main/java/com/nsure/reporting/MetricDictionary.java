package com.nsure.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.InvalidFieldException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The metric dictionary as a governed, versioned record (NS-SH13-003): each metric's numerator and denominator, event
 * or accounting date basis, exclusions, currency, time window and owner. A change is proposed as a new version and
 * applies only when someone else approves it; definitions that need actuarial or business approval (persistency,
 * claims ratio) show as awaiting approval on every dashboard figure that uses them until approved.
 */
@Service
public class MetricDictionary {

    public static final String APPROVED = "APPROVED";
    public static final String PENDING = "PENDING_APPROVAL";
    public static final String SUPERSEDED = "SUPERSEDED";

    private static final String[][] FIELDS = {{"name", "name"}, {"definition", "definition"},
        {"numerator", "numerator"}, {"denominator", "denominator"}, {"unit", "unit"}, {"currency", "currency"},
        {"dateBasis", "date_basis"}, {"timeBasis", "time_basis"}, {"timeWindow", "time_window"},
        {"exclusions", "exclusions"}, {"owner", "owner"}};

    private final JdbcTemplate jdbc;
    private final PlatformSecurityContext security;
    private final EvidenceChain audit;

    public MetricDictionary(JdbcTemplate jdbc, PlatformSecurityContext security, EvidenceChain audit) {
        this.jdbc = jdbc;
        this.security = security;
        this.audit = audit;
    }

    /** Every version, current first within each metric. READ_REPORT. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        security.authenticatedUser().validateHasPermissionTo("READ_REPORT");
        return jdbc.query("select * from rpt_metric_definition order by metric_key, version_no desc", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", rs.getString("metric_key"));
            m.put("version", rs.getInt("version_no"));
            for (String[] f : FIELDS) {
                m.put(f[0], rs.getString(f[1]));
            }
            m.put("requiresApproval", rs.getBoolean("requires_approval"));
            m.put("status", rs.getString("status"));
            m.put("changeNote", rs.getString("change_note"));
            m.put("proposedBy", rs.getString("proposed_by"));
            m.put("proposedAt", rs.getTimestamp("proposed_at").toLocalDateTime());
            m.put("approvedBy", rs.getString("approved_by"));
            m.put("approvedAt", rs.getTimestamp("approved_at") == null ? null
                    : rs.getTimestamp("approved_at").toLocalDateTime());
            return m;
        });
    }

    /** Per metric key: APPROVED when an approved version applies, otherwise the latest version's status. */
    public Map<String, String> statuses() {
        Map<String, String> out = new HashMap<>();
        jdbc.query("select metric_key, status from rpt_metric_definition order by metric_key, version_no desc", rs -> {
            String key = rs.getString(1);
            String status = rs.getString(2);
            if (APPROVED.equals(status) || !out.containsKey(key)) {
                out.put(key, APPROVED.equals(out.get(key)) ? APPROVED : status);
            }
        });
        return out;
    }

    /** The definition that applies to a key (approved, else the latest), for drill-through headers. */
    public Map<String, Object> current(String key) {
        if (key == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.query("select * from rpt_metric_definition where metric_key = ?"
                + " order by case when status = 'APPROVED' then 0 else 1 end, version_no desc", (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", key);
                    m.put("version", rs.getInt("version_no"));
                    for (String[] f : FIELDS) {
                        m.put(f[0], rs.getString(f[1]));
                    }
                    m.put("status", rs.getString("status"));
                    return m;
                }, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * {changeNote, and any of name, definition, numerator, denominator, unit, currency, dateBasis, timeBasis,
     * timeWindow, exclusions, owner}: proposes the next version of a metric, copied from the latest with the changes.
     */
    @Transactional
    public Map<String, Object> propose(String key, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_METRIC");
        Map<String, Object> latest = jdbc.query("select * from rpt_metric_definition where metric_key = ?"
                + " order by version_no desc", rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> m = new HashMap<>();
                    for (String[] f : FIELDS) {
                        m.put(f[1], rs.getString(f[1]));
                    }
                    m.put("version_no", rs.getInt("version_no"));
                    m.put("requires_approval", rs.getBoolean("requires_approval"));
                    m.put("status", rs.getString("status"));
                    return m;
                }, key);
        if (latest == null) {
            throw new NotFoundException("Metric " + key + " not found");
        }
        String note = body == null || !body.hasNonNull("changeNote") ? "" : body.get("changeNote").asText().trim();
        if (note.length() < 5) {
            throw new InvalidFieldException("changeNote", "Say what changes and why (at least 5 characters).");
        }
        for (String[] f : FIELDS) {
            if (body.hasNonNull(f[0]) && !body.get(f[0]).asText().isBlank()) {
                latest.put(f[1], body.get(f[0]).asText().trim());
            }
        }
        int version = (Integer) latest.get("version_no") + 1;
        // A newer proposal replaces one still awaiting approval; the approved version applies until it is approved.
        jdbc.update("update rpt_metric_definition set status = ? where metric_key = ? and status = ?", SUPERSEDED, key,
                PENDING);
        jdbc.update("insert into rpt_metric_definition (metric_key, version_no, name, definition, numerator, denominator,"
                + " unit, currency, date_basis, time_basis, time_window, exclusions, owner, requires_approval, status,"
                + " change_note, proposed_by, proposed_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                key, version, latest.get("name"), latest.get("definition"), latest.get("numerator"),
                latest.get("denominator"), latest.get("unit"), latest.get("currency"), latest.get("date_basis"),
                latest.get("time_basis"), latest.get("time_window"), latest.get("exclusions"), latest.get("owner"),
                latest.get("requires_approval"), PENDING, note, user.getUsername(), LocalDateTime.now());
        audit.record(user.getUsername(), "Metric definition proposed", "Metric", key, "v" + version + ": " + note);
        return Map.of("key", key, "version", version, "status", PENDING);
    }

    /** Approves a version someone else proposed; the version it replaces is kept as superseded. */
    @Transactional
    public Map<String, Object> approve(String key, int version) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("APPROVE_METRIC");
        List<String> proposer = jdbc.queryForList("select proposed_by from rpt_metric_definition where metric_key = ?"
                + " and version_no = ? and status = ?", String.class, key, version, PENDING);
        if (proposer.isEmpty()) {
            throw new ConflictException(key + " v" + version + " is not awaiting approval.");
        }
        if (user.getUsername().equalsIgnoreCase(proposer.get(0))) {
            throw new NoAuthorizationException("You proposed this definition, so someone else must approve it.");
        }
        jdbc.update("update rpt_metric_definition set status = ? where metric_key = ? and status = ?", SUPERSEDED, key,
                APPROVED);
        jdbc.update("update rpt_metric_definition set status = ?, approved_by = ?, approved_at = ? where metric_key = ?"
                + " and version_no = ?", APPROVED, user.getUsername(), LocalDateTime.now(), key, version);
        audit.record(user.getUsername(), "Metric definition approved", "Metric", key, "v" + version);
        return Map.of("key", key, "version", version, "status", APPROVED);
    }
}
