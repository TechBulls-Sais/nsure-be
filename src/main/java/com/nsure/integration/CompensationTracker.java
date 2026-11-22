package com.nsure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.security.service.PlatformSecurityContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * NS-TXN-004: every compensating event (a returned premium, a cancellation, a refund) with the legs it must correct —
 * journal, commission, reinsurance, customer communication — and whether each leg actually completed. A leg is read
 * from its consumer's delivery and, where the consumer records one, the effect it created for that event; a leg no
 * consumer handles is shown as a manual correction for its owner. Any compensation not complete is an exception case
 * in the integration monitor, with the original event it references.
 */
@Service
public class CompensationTracker {

    /** One leg a compensating event of a type must correct: its consumer (null when none is wired) and owner. */
    record Leg(String name, String handler, String owner, String effectSql) {
    }

    private static final Map<String, List<Leg>> LEGS = new LinkedHashMap<>();

    static {
        Leg journal = new Leg("Journal reversal", "finance.subledger", "Finance",
                "select count(*) from fin_journal where event_key = ?");
        Leg commission = new Leg("Commission clawback", "distribution.commission", "Distribution",
                "select count(*) from dist_commission where source_event_id = ?");
        Leg cession = new Leg("Reinsurance adjustment", "reinsurance.cession", "Reinsurance",
                "select count(*) from ri_cession where source_event_id = ?");
        Leg message = new Leg("Customer communication", "communications.notify", "Customer service",
                "select count(*) from com_message where event_id = ?");
        LEGS.put(Events.PAYMENT_RETURNED, List.of(journal, commission, cession, message));
        LEGS.put(Events.POLICY_CANCELLED, List.of(commission, cession, message));
        LEGS.put(Events.PREMIUM_REFUNDED, List.of(journal, commission, cession, message));
    }

    private final JdbcTemplate jdbc;
    private final PlatformSecurityContext security;
    private final ObjectMapper mapper;

    public CompensationTracker(JdbcTemplate jdbc, PlatformSecurityContext security, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.security = security;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> compensations() {
        security.authenticatedUser().validateHasPermissionTo("READ_INTEGRATION");
        String in = String.join(",", LEGS.keySet().stream().map(t -> "?").toList());
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.query("select id, event_id, event_type, aggregate_ref, payload, occurred_at from int_outbox_event where"
                + " event_type in (" + in + ") order by id desc", rs -> {
                    if (out.size() >= 300) {
                        return;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("eventId", rs.getString("event_id"));
                    m.put("eventType", rs.getString("event_type"));
                    m.put("aggregateRef", rs.getString("aggregate_ref"));
                    m.put("occurredAt", rs.getTimestamp("occurred_at").toLocalDateTime());
                    m.put("payload", rs.getString("payload"));
                    m.put("outboxId", rs.getLong("id"));
                    out.add(m);
                }, LEGS.keySet().toArray());
        for (Map<String, Object> m : out) {
            JsonNode payload = read((String) m.remove("payload"));
            Long outboxId = (Long) m.remove("outboxId");
            String eventId = (String) m.get("eventId");
            m.put("amount", payload.hasNonNull("amount") ? payload.get("amount").asText() : null);
            m.put("original", original(payload));
            List<Map<String, Object>> legs = new ArrayList<>();
            boolean complete = true;
            for (Leg leg : LEGS.get((String) m.get("eventType"))) {
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("leg", leg.name());
                l.put("owner", leg.owner());
                List<Map<String, Object>> delivery = jdbc.queryForList("select status, last_error from int_event_delivery"
                        + " where outbox_event_id = ? and handler_name = ?", outboxId, leg.handler());
                String state;
                String detail;
                if (delivery.isEmpty()) {
                    state = "NOT_AUTOMATED";
                    detail = "No consumer corrects this leg automatically: " + leg.owner() + " checks and corrects it"
                            + " by hand.";
                    complete = false;
                } else {
                    String status = String.valueOf(value(delivery.get(0), "status"));
                    Long effects = jdbc.queryForObject(leg.effectSql(), Long.class, eventId);
                    if ("DELIVERED".equals(status)) {
                        state = effects != null && effects > 0 ? "DONE" : "NOTHING_TO_CORRECT";
                        detail = effects != null && effects > 0 ? effects + " correcting record(s) reference this event"
                                : "Handled; there was nothing of this kind to reverse";
                    } else if ("IGNORED".equals(status)) {
                        state = "NOTHING_TO_CORRECT";
                        detail = String.valueOf(value(delivery.get(0), "last_error"));
                    } else {
                        state = "FAILED".equals(status) ? "FAILED" : "IN_PROGRESS";
                        detail = value(delivery.get(0), "last_error") == null ? "Waiting for " + leg.handler()
                                : String.valueOf(value(delivery.get(0), "last_error"));
                        complete = false;
                    }
                }
                l.put("state", state);
                l.put("detail", detail);
                legs.add(l);
            }
            m.put("legs", legs);
            m.put("status", complete ? "COMPLETE" : legs.stream().anyMatch(l -> "FAILED".equals(l.get("state")))
                    ? "EXCEPTION" : legs.stream().anyMatch(l -> "IN_PROGRESS".equals(l.get("state"))) ? "IN_PROGRESS"
                    : "MANUAL_FOLLOW_UP");
        }
        return out;
    }

    /** The event this compensation reverses, found through the receipt or policy it references. */
    private Map<String, Object> original(JsonNode payload) {
        String receipt = payload.hasNonNull("receiptRef") ? payload.get("receiptRef").asText() : null;
        if (receipt == null) {
            return null;
        }
        return jdbc.query("select event_id, event_type, occurred_at from int_outbox_event where event_type = ? and"
                + " payload like ? order by id", rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("eventId", rs.getString(1));
                    o.put("eventType", rs.getString(2));
                    o.put("occurredAt", rs.getTimestamp(3).toLocalDateTime());
                    o.put("receiptRef", receipt);
                    return o;
                }, Events.PREMIUM_RECEIVED, "%\"receiptRef\":\"" + receipt + "\"%");
    }

    private JsonNode read(String payload) {
        try {
            return mapper.readTree(payload);
        } catch (Exception ex) {
            return mapper.createObjectNode();
        }
    }

    private static Object value(Map<String, Object> row, String column) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(column)) {
                return e.getValue();
            }
        }
        return null;
    }
}
