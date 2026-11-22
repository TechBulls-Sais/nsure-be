package com.nsure.integration;

import com.nsure.audit.evidence.EvidenceChain;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Every operator act on the integration layer (replay, retry now, outage switch, resumption) with its reason and
 * outcome (NS-SH12-005): kept in the monitor's own log and as chained audit evidence.
 */
@Component
public class OperatorActionLog {

    private final JdbcTemplate jdbc;
    private final EvidenceChain audit;

    public OperatorActionLog(JdbcTemplate jdbc, EvidenceChain audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public void record(String action, String interfaceCode, String targetRef, String reason, String outcome,
            String actor) {
        jdbc.update("insert into int_operator_action (action, interface_code, target_ref, reason, outcome, actor,"
                + " acted_at) values (?, ?, ?, ?, ?, ?, ?)", action, interfaceCode, clip(targetRef, 80),
                clip(reason, 500), clip(outcome, 500), actor, LocalDateTime.now());
        audit.record(actor, "Integration: " + action, "Interface", interfaceCode == null ? targetRef : interfaceCode,
                (targetRef == null ? "" : targetRef + ": ") + (reason == null ? "" : reason)
                        + (outcome == null ? "" : " → " + outcome));
    }

    public List<Map<String, Object>> recent(int limit) {
        return jdbc.query("select * from int_operator_action order by id desc", rs -> {
            List<Map<String, Object>> out = new java.util.ArrayList<>();
            while (rs.next() && out.size() < limit) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("action", rs.getString("action"));
                m.put("interfaceCode", rs.getString("interface_code"));
                m.put("targetRef", rs.getString("target_ref"));
                m.put("reason", rs.getString("reason"));
                m.put("outcome", rs.getString("outcome"));
                m.put("actor", rs.getString("actor"));
                m.put("actedAt", rs.getTimestamp("acted_at").toLocalDateTime());
                out.add(m);
            }
            return out;
        });
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
