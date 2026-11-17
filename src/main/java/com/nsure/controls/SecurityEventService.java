package com.nsure.controls;

import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Security events have an owner and an escalation route (SH11 "Records and workspace"). The alert itself stays
 * immutable; its handling is kept beside it: someone who may act on it takes it, and only they resolve it with a
 * written outcome (audit evidence). An event nobody has taken within {@code escalate-after-hours} is escalated once to
 * the security administrators (holders of {@value #ESCALATION_PERMISSION}).
 */
@Service
public class SecurityEventService {

    /** The alert types that are security events. */
    public static final Set<String> TYPES = Set.of("EMERGENCY_ACCESS_GRANTED", "EMERGENCY_ACCESS_REVOKED",
            "SOLE_CHECKER_BOOTSTRAP", "CONTROL_DISABLED", "PAYMENT_TAMPER", "ACCESS_REVIEW_REVOKE");
    static final String ESCALATION_PERMISSION = "GRANT_EMERGENCY_ACCESS";
    static final String ESCALATED = "SECURITY_EVENT_ESCALATED";

    private final JdbcTemplate jdbc;
    private final PlatformSecurityContext security;
    private final StaffAlertService alerts;
    private final EvidenceChain evidence;
    private final int escalateAfterHours;

    public SecurityEventService(JdbcTemplate jdbc, PlatformSecurityContext security, StaffAlertService alerts,
            EvidenceChain evidence, @Value("${nsure.security-events.escalate-after-hours:4}") int escalateAfterHours) {
        this.jdbc = jdbc;
        this.security = security;
        this.alerts = alerts;
        this.evidence = evidence;
        this.escalateAfterHours = escalateAfterHours;
    }

    /** The last 200 security events with who handles them, for auditors (READ_AUDIT). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("READ_AUDIT");
        List<Map<String, Object>> rows = jdbc.query(select() + " order by a.created_at desc, a.id desc",
                SecurityEventService::row);
        for (Map<String, Object> event : rows.subList(0, Math.min(rows.size(), 200))) {
            event.put("canHandle", user.hasPermissionTo((String) event.get("requiredPermission")));
        }
        return rows.subList(0, Math.min(rows.size(), 200));
    }

    /** Takes the event; only someone holding the permission the event was raised for, and only if nobody has it. */
    @Transactional
    public Map<String, Object> own(Long id) {
        AppUser user = security.authenticatedUser();
        Map<String, Object> event = find(id);
        user.validateHasPermissionTo((String) event.get("requiredPermission"));
        if (event.get("resolvedAt") != null) {
            throw new ConflictException("This security event is already resolved.");
        }
        String owner = (String) event.get("owner");
        if (owner != null && !owner.equals(user.getUsername())) {
            throw new ConflictException("This security event is already owned by " + owner + ".");
        }
        ensureHandling(id);
        jdbc.update("update security_event_handling set owner = ?, owned_at = ? where alert_id = ?",
                user.getUsername(), LocalDateTime.now(), id);
        evidence.record(user.getUsername(), "Security event taken", "Security event", String.valueOf(id),
                (String) event.get("title"));
        return find(id);
    }

    /** {resolution}: the owner records what was found and done. */
    @Transactional
    public Map<String, Object> resolve(Long id, String resolution) {
        AppUser user = security.authenticatedUser();
        Map<String, Object> event = find(id);
        if (!user.getUsername().equals(event.get("owner"))) {
            throw new NoAuthorizationException("Only the owner of a security event resolves it; take it first.");
        }
        if (event.get("resolvedAt") != null) {
            throw new ConflictException("This security event is already resolved.");
        }
        String text = resolution == null ? "" : resolution.trim();
        if (text.length() < 5 || text.length() > 500) {
            throw new IllegalArgumentException("Record what was found and done (5 to 500 characters).");
        }
        jdbc.update("update security_event_handling set resolved_by = ?, resolved_at = ?, resolution = ? where"
                + " alert_id = ?", user.getUsername(), LocalDateTime.now(), text, id);
        evidence.record(user.getUsername(), "Security event resolved", "Security event", String.valueOf(id),
                event.get("title") + ": " + text);
        return find(id);
    }

    /** Escalates, once, every security event nobody has taken within the allowed time. */
    @Scheduled(cron = "${nsure.security-events.escalation-cron:0 */15 * * * *}")
    @Transactional
    public int escalate() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(escalateAfterHours);
        int escalated = 0;
        for (Map<String, Object> event : jdbc.query(select() + " and a.created_at < ? and h.owner is null and"
                + " h.resolved_at is null and h.escalated_at is null", SecurityEventService::row, cutoff)) {
            Long id = ((Number) event.get("id")).longValue();
            ensureHandling(id);
            jdbc.update("update security_event_handling set escalated_at = ? where alert_id = ?", LocalDateTime.now(),
                    id);
            alerts.raise(ESCALATED, ESCALATION_PERMISSION, "Unhandled security event: " + event.get("title"),
                    "Nobody has taken this security event within " + escalateAfterHours + " hour(s). Take it under"
                            + " Access > Security events. " + event.get("body"),
                    "SECURITY_EVENT_ESCALATED:" + id, "system");
            escalated++;
        }
        return escalated;
    }

    private String select() {
        String types = TYPES.stream().sorted().map(t -> "'" + t + "'").collect(Collectors.joining(", "));
        // Every event gets a handling row lazily; a left join with nulls treats a missing row as open.
        return "select a.id, a.alert_type, a.required_permission, a.title, a.body, a.created_by, a.created_at,"
                + " h.owner, h.owned_at, h.resolved_by, h.resolved_at, h.resolution, h.escalated_at from staff_alert a"
                + " left join security_event_handling h on h.alert_id = a.id where a.alert_type in (" + types + ")";
    }

    private Map<String, Object> find(Long id) {
        return jdbc.query(select() + " and a.id = ?", SecurityEventService::row, id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Security event " + id + " not found"));
    }

    private void ensureHandling(Long id) {
        Integer exists = jdbc.queryForObject("select count(*) from security_event_handling where alert_id = ?",
                Integer.class, id);
        if (exists == null || exists == 0) {
            jdbc.update("insert into security_event_handling (alert_id) values (?)", id);
        }
    }

    private static Map<String, Object> row(ResultSet rs, int n) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            String[] parts = rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT).split("_");
            StringBuilder key = new StringBuilder(parts[0]);
            for (int p = 1; p < parts.length; p++) {
                key.append(Character.toUpperCase(parts[p].charAt(0))).append(parts[p].substring(1));
            }
            Object v = rs.getObject(i);
            if (v instanceof java.sql.Timestamp t) {
                v = t.toLocalDateTime();
            }
            m.put(key.toString(), v);
        }
        m.put("status", m.get("resolvedAt") != null ? "RESOLVED" : m.get("owner") != null ? "OWNED" : "OPEN");
        return m;
    }
}
