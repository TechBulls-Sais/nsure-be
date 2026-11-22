package com.nsure.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.InvalidFieldException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.controls.StaffAlertService;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Governance around the rule-based assistants (section 15; no AI model is called):
 * <ul>
 *   <li>NS-AIG-001: a version change of an assistant's rules is its own maker-checker record, separate from wording
 *       and template edits; the running version changes only when a second person approves it.</li>
 *   <li>NS-AIG-005: monitoring of every assistant against its agreed threshold — reviewed outputs, acceptance,
 *       overrides and rejections (false positives), review backlog (workload) and drift between the last 30 days and
 *       before. An assistant below its threshold is flagged and alerted.</li>
 *   <li>Section 17: the RFP AI coverage register and the scope-exception register, each item with its proposed
 *       disposition and the insurer decision it awaits, recorded by an authorised person.</li>
 * </ul>
 */
@Service
public class AssistantGovernance {

    /** Reviews needed before an acceptance rate is judged against the threshold. Demo default. */
    static final int MIN_REVIEWS = 5;

    private final JdbcTemplate jdbc;
    private final PlatformSecurityContext security;
    private final EvidenceChain audit;
    private final StaffAlertService alerts;

    public AssistantGovernance(JdbcTemplate jdbc, PlatformSecurityContext security, EvidenceChain audit,
            StaffAlertService alerts) {
        this.jdbc = jdbc;
        this.security = security;
        this.audit = audit;
        this.alerts = alerts;
    }

    // ── version changes ────────────────────────────────────────────────────────────────────────────

    /** {toVersion, changeNote, validationSummary?}: asks for an assistant's rules to move to a new version. */
    @Transactional
    public Map<String, Object> requestChange(String code, JsonNode body) {
        AppUser user = user("MANAGE_CONFIG");
        List<String> current = jdbc.queryForList("select model_version from ai_use_case where code = ?", String.class,
                code);
        if (current.isEmpty()) {
            throw new NotFoundException("Assistant " + code + " not found");
        }
        String to = text(body, "toVersion");
        String note = text(body, "changeNote");
        if (to == null || to.length() > 40) {
            throw new InvalidFieldException("toVersion", "Name the new version (up to 40 characters).");
        }
        if (to.equals(current.get(0))) {
            throw new InvalidFieldException("toVersion", code + " already runs " + to + ".");
        }
        if (note == null || note.length() < 10) {
            throw new InvalidFieldException("changeNote", "Say what changes and why (at least 10 characters).");
        }
        Integer open = jdbc.queryForObject("select count(*) from ai_model_change where use_case = ? and status ="
                + " 'PENDING'", Integer.class, code);
        if (open != null && open > 0) {
            throw new ConflictException("A version change for " + code + " is already awaiting approval.");
        }
        jdbc.update("insert into ai_model_change (use_case, from_version, to_version, change_note, validation_summary,"
                + " requested_by, requested_at, status) values (?, ?, ?, ?, ?, ?, ?, 'PENDING')", code, current.get(0),
                to, note, text(body, "validationSummary"), user.getUsername(), LocalDateTime.now());
        audit.record(user.getUsername(), "Assistant version change requested", "Assistant", code,
                current.get(0) + " → " + to + ": " + note);
        return Map.of("useCase", code, "toVersion", to, "status", "PENDING");
    }

    /** {approve, note}: a second person approves (the running version changes) or rejects a version change. */
    @Transactional
    public Map<String, Object> decideChange(Long id, JsonNode body) {
        AppUser user = user("APPROVE_CONFIG");
        Map<String, Object> change = jdbc.query("select use_case, to_version, requested_by, status from ai_model_change"
                + " where id = ?", rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("useCase", rs.getString(1));
                    m.put("to", rs.getString(2));
                    m.put("by", rs.getString(3));
                    m.put("status", rs.getString(4));
                    return m;
                }, id);
        if (change == null) {
            throw new NotFoundException("Version change " + id + " not found");
        }
        if (!"PENDING".equals(change.get("status"))) {
            throw new ConflictException("That version change was already decided.");
        }
        if (user.getUsername().equalsIgnoreCase((String) change.get("by"))) {
            throw new NoAuthorizationException("You asked for this change, so someone else must decide it.");
        }
        boolean approve = body != null && body.path("approve").asBoolean(false);
        String note = text(body, "note");
        if (!approve && (note == null || note.length() < 5)) {
            throw new InvalidFieldException("note", "Say why the change is rejected.");
        }
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("update ai_model_change set status = ?, decided_by = ?, decided_at = ?, decision_note = ? where id = ?",
                approve ? "APPROVED" : "REJECTED", user.getUsername(), now, note, id);
        if (approve) {
            jdbc.update("update ai_use_case set model_version = ?, approval_status = 'APPROVED', approved_by = ?,"
                    + " approved_at = ?, updated_by = ?, updated_at = ? where code = ?", change.get("to"),
                    user.getUsername(), now, user.getUsername(), now, change.get("useCase"));
        }
        audit.record(user.getUsername(), "Assistant version change " + (approve ? "approved" : "rejected"), "Assistant",
                (String) change.get("useCase"), change.get("to") + (note == null ? "" : ": " + note));
        return Map.of("id", id, "status", approve ? "APPROVED" : "REJECTED");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> changes() {
        security.authenticatedUser();
        return jdbc.query("select * from ai_model_change order by id desc", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("useCase", rs.getString("use_case"));
            m.put("fromVersion", rs.getString("from_version"));
            m.put("toVersion", rs.getString("to_version"));
            m.put("changeNote", rs.getString("change_note"));
            m.put("validationSummary", rs.getString("validation_summary"));
            m.put("requestedBy", rs.getString("requested_by"));
            m.put("requestedAt", rs.getTimestamp("requested_at").toLocalDateTime());
            m.put("status", rs.getString("status"));
            m.put("decidedBy", rs.getString("decided_by"));
            m.put("decisionNote", rs.getString("decision_note"));
            return m;
        });
    }

    // ── monitoring ─────────────────────────────────────────────────────────────────────────────────

    /** Per assistant: outputs, review outcomes, acceptance against its threshold, backlog and drift. */
    @Transactional
    public List<Map<String, Object>> monitoring() {
        security.authenticatedUser();
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.query("select code, name, acceptance_threshold, enabled from ai_use_case order by code", rs -> {
            String code = rs.getString(1);
            int threshold = rs.getInt(3);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("name", rs.getString(2));
            m.put("enabled", rs.getBoolean(4));
            m.put("threshold", threshold);
            out.add(m);
        });
        for (Map<String, Object> m : out) {
            String code = (String) m.get("code");
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String s : List.of("PENDING", "ACCEPTED", "OVERRIDDEN", "REJECTED")) {
                counts.put(s, 0L);
            }
            jdbc.query("select review_status, count(*) from ai_interaction where use_case = ? group by review_status",
                    rs -> {
                        counts.put(rs.getString(1), rs.getLong(2));
                    }, code);
            long reviewed = counts.get("ACCEPTED") + counts.get("OVERRIDDEN") + counts.get("REJECTED");
            BigDecimal acceptance = rate(counts.get("ACCEPTED"), reviewed);
            BigDecimal recent = rate(jdbc.queryForObject("select count(*) from ai_interaction where use_case = ? and"
                    + " review_status = 'ACCEPTED' and requested_at >= ?", Long.class, code, since),
                    jdbc.queryForObject("select count(*) from ai_interaction where use_case = ? and review_status <>"
                            + " 'PENDING' and requested_at >= ?", Long.class, code, since));
            BigDecimal earlier = rate(jdbc.queryForObject("select count(*) from ai_interaction where use_case = ? and"
                    + " review_status = 'ACCEPTED' and requested_at < ?", Long.class, code, since),
                    jdbc.queryForObject("select count(*) from ai_interaction where use_case = ? and review_status <>"
                            + " 'PENDING' and requested_at < ?", Long.class, code, since));
            String status = reviewed < MIN_REVIEWS ? "INSUFFICIENT_DATA"
                    : acceptance.compareTo(BigDecimal.valueOf((Integer) m.get("threshold"))) < 0 ? "BELOW_THRESHOLD"
                    : "WITHIN_THRESHOLD";
            m.put("outputs", counts.values().stream().mapToLong(Long::longValue).sum());
            m.put("awaitingReview", counts.get("PENDING"));
            m.put("accepted", counts.get("ACCEPTED"));
            m.put("overridden", counts.get("OVERRIDDEN"));
            m.put("rejected", counts.get("REJECTED"));
            m.put("acceptanceRate", reviewed == 0 ? null : acceptance);
            m.put("overrideRate", reviewed == 0 ? null : rate(counts.get("OVERRIDDEN"), reviewed));
            m.put("falsePositiveRate", reviewed == 0 ? null : rate(counts.get("REJECTED"), reviewed));
            m.put("acceptanceLast30Days", recent.signum() == 0 && earlier.signum() == 0 ? null : recent);
            m.put("drift", earlier.signum() == 0 ? null : recent.subtract(earlier));
            m.put("status", status);
            if ("BELOW_THRESHOLD".equals(status)) {
                alerts.raise("ASSISTANT_BELOW_THRESHOLD", "MANAGE_CONFIG", m.get("name") + " is below its threshold",
                        "Acceptance " + acceptance + "% over " + reviewed + " reviews is below the agreed "
                                + m.get("threshold") + "%. Review the rules or switch the assistant off; the manual"
                                + " process is unaffected.", "assistant-threshold:" + code + ":" + LocalDate.now(),
                        "system");
            }
        }
        return out;
    }

    private static BigDecimal rate(Long part, Long whole) {
        if (whole == null || whole == 0 || part == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(part * 100.0 / whole).setScale(1, RoundingMode.HALF_UP);
    }

    // ── RFP AI coverage and scope exceptions (section 17) ──────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> dispositions() {
        security.authenticatedUser();
        return jdbc.query("select * from ai_rfp_disposition order by rfp_range", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("range", rs.getString("rfp_range"));
            m.put("capability", rs.getString("capability"));
            m.put("disposition", rs.getString("disposition"));
            m.put("deliveredBy", rs.getString("delivered_by"));
            m.put("controls", rs.getString("controls"));
            m.put("limits", rs.getString("limits"));
            m.put("decisionRef", rs.getString("decision_ref"));
            m.put("owner", rs.getString("owner"));
            m.put("status", rs.getString("status"));
            return m;
        });
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> scopeExceptions() {
        security.authenticatedUser();
        return jdbc.query("select * from scope_exception order by code", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", rs.getString("code"));
            m.put("requirement", rs.getString("requirement"));
            m.put("currentPosition", rs.getString("current_position"));
            m.put("proposedDisposition", rs.getString("proposed_disposition"));
            m.put("decisionRef", rs.getString("decision_ref"));
            m.put("owner", rs.getString("owner"));
            m.put("status", rs.getString("status"));
            m.put("decisionNote", rs.getString("decision_note"));
            m.put("decidedBy", rs.getString("decided_by"));
            m.put("decidedAt", rs.getTimestamp("decided_at") == null ? null
                    : rs.getTimestamp("decided_at").toLocalDateTime());
            return m;
        });
    }

    /**
     * {decision ACCEPTED | REJECTED, note}: records the insurer's decision on a proposed scope exception, as an
     * authorised person keyed it in from the signed decision (the decision itself is the insurer's).
     */
    @Transactional
    public Map<String, Object> decideScope(String code, JsonNode body) {
        AppUser user = user("APPROVE_CONFIG");
        String decision = text(body, "decision") == null ? null : text(body, "decision").toUpperCase(Locale.ROOT);
        String note = text(body, "note");
        if (!"ACCEPTED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new InvalidFieldException("decision", "decision must be ACCEPTED or REJECTED.");
        }
        if (note == null || note.length() < 10) {
            throw new InvalidFieldException("note", "Quote the insurer's decision reference and date (at least 10"
                    + " characters).");
        }
        if (jdbc.update("update scope_exception set status = ?, decision_note = ?, decided_by = ?, decided_at = ?"
                + " where code = ? and status = 'PROPOSED'", decision, note, user.getUsername(), LocalDateTime.now(),
                code) == 0) {
            throw new ConflictException("Scope item " + code + " is unknown or already decided.");
        }
        audit.record(user.getUsername(), "Scope exception " + decision.toLowerCase(Locale.ROOT), "ScopeException", code,
                note);
        return Map.of("code", code, "status", decision);
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    private static String text(JsonNode body, String field) {
        return body != null && body.hasNonNull(field) && !body.get(field).asText().isBlank()
                ? body.get(field).asText().trim() : null;
    }
}
