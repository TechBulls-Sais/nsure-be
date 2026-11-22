package com.nsure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.NotFoundException;
import com.nsure.controls.StaffAlertService;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The SH12 workspace beyond the event list: the interface catalogue with live health per interface (deliveries,
 * latency, backlog, dead letters), the simulated availability switch that demonstrates an outage and its recovery
 * (AT-SH12-02), the batch monitor across census, statement, GL, bordereau and extract batches, and the per-interface
 * reconciliation totals (NS-SH12-005). Read-only over other modules' tables; only the catalogue's own rows change.
 */
@Service
public class InterfaceCatalogue {

    private final JdbcTemplate jdbc;
    private final PlatformSecurityContext security;
    private final InterfaceRegistry registry;
    private final OutboxDispatcher dispatcher;
    private final OperatorActionLog actions;
    private final StaffAlertService alerts;
    private final boolean simulator;
    private final PaymentCallbacks payments;
    private final ScreeningService screening;

    public InterfaceCatalogue(JdbcTemplate jdbc, PlatformSecurityContext security, InterfaceRegistry registry,
            OutboxDispatcher dispatcher, OperatorActionLog actions, StaffAlertService alerts,
            @Value("${nsure.integration.provider-simulator:true}") boolean simulator, PaymentCallbacks payments,
            ScreeningService screening) {
        this.payments = payments;
        this.screening = screening;
        this.jdbc = jdbc;
        this.security = security;
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.actions = actions;
        this.alerts = alerts;
        this.simulator = simulator;
    }

    // ── catalogue and health ─────────────────────────────────────────────────────────────────────────

    /** Every registered interface with its register entry and live health, plus consumers no interface claims. */
    public Map<String, Object> catalogue() {
        security.authenticatedUser().validateHasPermissionTo("READ_INTEGRATION");
        Map<String, Map<String, Long>> byHandler = new HashMap<>();
        Map<String, LocalDateTime> oldestPending = new HashMap<>();
        Map<String, LocalDateTime> lastDelivered = new HashMap<>();
        jdbc.query("select d.handler_name, d.status, count(*) n, min(case when d.status = 'PENDING' then e.occurred_at end)"
                + " oldest, max(d.delivered_at) last_done from int_event_delivery d join int_outbox_event e"
                + " on e.id = d.outbox_event_id group by d.handler_name, d.status", rs -> {
                    String handler = rs.getString(1);
                    byHandler.computeIfAbsent(handler, h -> new HashMap<>()).put(rs.getString(2), rs.getLong(3));
                    min(oldestPending, handler, time(rs.getTimestamp(4)));
                    max(lastDelivered, handler, time(rs.getTimestamp(5)));
                });
        Map<String, List<Long>> latencies = latencies();
        Map<String, Long> deadLetters = new HashMap<>();
        jdbc.query("select handler_name, count(*) from int_event_delivery where status = 'FAILED' group by handler_name",
                rs -> {
                    deadLetters.put(rs.getString(1), rs.getLong(2));
                });
        Set<String> claimed = new LinkedHashSet<>();
        List<Map<String, Object>> rows = jdbc.query("select * from int_interface order by sort_order, code", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String[] c : new String[][] {{"code", "code"}, {"name", "name"}, {"kind", "kind"},
                    {"direction", "direction"}, {"counterpart", "counterpart"}, {"pattern", "pattern"},
                    {"authentication", "authentication"}, {"dataContract", "data_contract"}, {"frequency", "frequency"},
                    {"sla", "sla"}, {"reconciliation", "reconciliation"}, {"retention", "retention"},
                    {"runbook", "runbook"}, {"systemOwner", "system_owner"}, {"exceptionOwner", "exception_owner"},
                    {"connection", "connection_mode"}, {"availabilityNote", "availability_note"},
                    {"availabilityChangedBy", "availability_changed_by"}}) {
                m.put(c[0], rs.getString(c[1]));
            }
            m.put("available", rs.getBoolean("available"));
            m.put("availabilityChangedAt", time(rs.getTimestamp("availability_changed_at")));
            String handlers = rs.getString("handlers");
            m.put("handlers", handlers == null || handlers.isBlank() ? List.of() : List.of(handlers.split(",")));
            return m;
        });
        for (Map<String, Object> m : rows) {
            @SuppressWarnings("unchecked")
            List<String> handlers = (List<String>) m.get("handlers");
            claimed.addAll(handlers);
            m.put("simulatable", simulator && !"MANUAL".equals(m.get("connection"))
                    && !"NOT_CONNECTED".equals(m.get("connection")));
            m.put("health", health(handlers, byHandler, oldestPending, lastDelivered, latencies, deadLetters));
        }
        List<String> unregistered = byHandler.keySet().stream().filter(h -> !claimed.contains(h)).sorted().toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("interfaces", rows);
        out.put("unregisteredConsumers", unregistered);
        out.put("simulator", simulator);
        out.put("asOf", LocalDateTime.now());
        return out;
    }

    private static Map<String, Object> health(List<String> handlers, Map<String, Map<String, Long>> byHandler,
            Map<String, LocalDateTime> oldestPending, Map<String, LocalDateTime> lastDelivered,
            Map<String, List<Long>> latencies, Map<String, Long> deadLetters) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : List.of(EventDelivery.PENDING, EventDelivery.DELIVERED, EventDelivery.FAILED,
                EventDelivery.IGNORED)) {
            counts.put(s, 0L);
        }
        LocalDateTime oldest = null;
        LocalDateTime last = null;
        List<Long> seconds = new ArrayList<>();
        long dead = 0;
        for (String h : handlers) {
            byHandler.getOrDefault(h, Map.of()).forEach((status, n) -> counts.merge(status, n, Long::sum));
            oldest = earlier(oldest, oldestPending.get(h));
            last = later(last, lastDelivered.get(h));
            seconds.addAll(latencies.getOrDefault(h, List.of()));
            dead += deadLetters.getOrDefault(h, 0L);
        }
        long done = counts.get(EventDelivery.DELIVERED) + counts.get(EventDelivery.IGNORED);
        long attempted = done + counts.get(EventDelivery.FAILED);
        seconds.sort(Comparator.naturalOrder());
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("deliveries", counts);
        h.put("successRate", attempted == 0 ? null
                : BigDecimal.valueOf(done * 1000 / attempted, 1));
        h.put("averageLatencySeconds", seconds.isEmpty() ? null
                : seconds.stream().mapToLong(Long::longValue).sum() / seconds.size());
        h.put("p95LatencySeconds", seconds.isEmpty() ? null
                : seconds.get(Math.min(seconds.size() - 1, (int) Math.ceil(seconds.size() * 0.95) - 1)));
        h.put("backlog", counts.get(EventDelivery.PENDING));
        h.put("oldestPending", oldest);
        h.put("lastDelivered", last);
        h.put("deadLetters", dead);
        return h;
    }

    /** Seconds from event to delivery for each consumer's deliveries of the last 30 days. */
    private Map<String, List<Long>> latencies() {
        Map<String, List<Long>> out = new HashMap<>();
        jdbc.query("select d.handler_name, e.occurred_at, d.delivered_at from int_event_delivery d join int_outbox_event e"
                + " on e.id = d.outbox_event_id where d.status = 'DELIVERED' and d.delivered_at >= ?", rs -> {
                    LocalDateTime from = time(rs.getTimestamp(2));
                    LocalDateTime to = time(rs.getTimestamp(3));
                    if (from != null && to != null) {
                        out.computeIfAbsent(rs.getString(1), h -> new ArrayList<>())
                                .add(Math.max(0, Duration.between(from, to).getSeconds()));
                    }
                }, LocalDateTime.now().minusDays(30));
        return out;
    }

    /**
     * {available, reason}: switches an interface's simulated availability. Off, its consumers' deliveries fail as
     * transient and queue up (a monitored backlog, with an alert to integration staff); back on, its transient dead
     * letters and waiting retries resume at once (AT-SH12-02). Simulator only; every switch is logged with its reason.
     */
    public Map<String, Object> setAvailability(String code, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_INTEGRATION");
        InterfaceRegistry.Entry entry = registry.entry(code)
                .orElseThrow(() -> new NotFoundException("Interface " + code + " not found"));
        if (!simulator) {
            throw new IllegalArgumentException("The provider simulator is off in this environment: availability comes"
                    + " from the real interface, not a switch.");
        }
        if ("MANUAL".equals(entry.connection()) || "NOT_CONNECTED".equals(entry.connection())) {
            throw new IllegalArgumentException(entry.name() + " has no automated connection to switch off.");
        }
        if (body == null || !body.has("available")) {
            throw new com.nsure.common.exception.InvalidFieldException("available", "Say whether the interface is available.");
        }
        boolean available = body.get("available").asBoolean();
        String reason = body.hasNonNull("reason") ? body.get("reason").asText().trim() : "";
        if (reason.length() < 5) {
            throw new com.nsure.common.exception.InvalidFieldException("reason", "Give a reason (at least 5 characters).");
        }
        if (available == entry.available()) {
            throw new com.nsure.common.exception.ConflictException(entry.name() + " is already "
                    + (available ? "available" : "unavailable") + ".");
        }
        jdbc.update("update int_interface set available = ?, availability_note = ?, availability_changed_by = ?,"
                + " availability_changed_at = ? where code = ?", available, available ? null : reason,
                user.getUsername(), LocalDateTime.now(), code);
        registry.invalidate();
        String outcome;
        if (available) {
            int resumed = dispatcher.resume(entry.handlers());
            OutboxDispatcher.DispatchResult run = resumed > 0 ? dispatcher.dispatch()
                    : new OutboxDispatcher.DispatchResult(0, 0);
            outcome = "Restored; " + resumed + " delivery(ies) resumed, " + run.delivered() + " delivered, "
                    + run.failed() + " failed";
            if ("BANK_PAYMENTS".equals(code)) {
                // NS-SH12-004: the provider answers again, so unknown payment outcomes are asked about now.
                outcome += "; " + payments.requeryUnknown() + " unknown payment outcome(s) resolved by status query";
            }
            if ("KYC_AML".equals(code)) {
                outcome += "; " + screening.retryPending() + " pending screening check(s) answered";
            }
            actions.record("INTERFACE_RESTORED", code, null, reason, outcome, user.getUsername());
        } else {
            outcome = "Marked unavailable (simulated outage)";
            actions.record("INTERFACE_OUTAGE", code, null, reason, outcome, user.getUsername());
            alerts.raise("INTERFACE_UNAVAILABLE", "READ_INTEGRATION", entry.name() + " is unavailable",
                    "Deliveries to " + entry.name() + " queue and retry until it is back: " + reason
                            + ". Owner of business exceptions: " + entry.exceptionOwner() + ".",
                    "interface-down:" + code + ":" + LocalDateTime.now().withNano(0), user.getUsername());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("available", available);
        out.put("outcome", outcome);
        return out;
    }

    public List<Map<String, Object>> operatorActions() {
        security.authenticatedUser().validateHasPermissionTo("READ_INTEGRATION");
        return actions.recent(200);
    }

    // ── batch monitor ────────────────────────────────────────────────────────────────────────────────

    /** Census, bank statement, GL, bordereau and extract batches in one list, newest first. */
    public List<Map<String, Object>> batches() {
        security.authenticatedUser().validateHasPermissionTo("READ_INTEGRATION");
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.query("select b.record_ref, b.external_batch_id, b.batch_status, b.total_rows, b.valid_rows, b.invalid_rows,"
                + " b.accepted_rows, b.rejected_rows, b.submitted_by, b.submitted_at, b.file_hash, s.record_ref scheme_ref"
                + " from grp_census_batch b left join ins_scheme s on s.id = b.scheme_id", rs -> {
                    out.add(batch("EMPLOYER_LENDER", "Census", rs.getString("record_ref"),
                            rs.getString("external_batch_id") + " · " + rs.getString("scheme_ref"),
                            rs.getString("batch_status"), rs.getInt("total_rows"),
                            "COMMITTED".equals(rs.getString("batch_status")) ? rs.getInt("accepted_rows")
                                    : rs.getInt("valid_rows"),
                            "COMMITTED".equals(rs.getString("batch_status")) ? rs.getInt("rejected_rows")
                                    : rs.getInt("invalid_rows"),
                            null, rs.getString("submitted_by"), time(rs.getTimestamp("submitted_at")),
                            rs.getString("file_hash")));
                });
        jdbc.query("select s.record_ref, s.provider, s.period_from, s.period_to, s.uploaded_by, s.uploaded_at,"
                + " (select count(*) from bil_recon_item i where i.statement_id = s.id) lines,"
                + " (select count(*) from bil_recon_item i where i.statement_id = s.id and i.status = 'MATCHED') matched,"
                + " (select sum(i.amount) from bil_recon_item i where i.statement_id = s.id) total from bil_statement s",
                rs -> {
                    int lines = rs.getInt("lines");
                    int matched = rs.getInt("matched");
                    out.add(batch("BANK_PAYMENTS", "Bank statement", rs.getString("record_ref"),
                            rs.getString("provider") + " " + rs.getDate("period_from") + " to " + rs.getDate("period_to"),
                            matched == lines ? "RECONCILED" : "EXCEPTIONS", lines, matched, lines - matched,
                            rs.getBigDecimal("total"), rs.getString("uploaded_by"), time(rs.getTimestamp("uploaded_at")),
                            null));
                });
        jdbc.query("select record_ref, status, journal_count, line_count, debit_total, created_by, created_at,"
                + " gl_reference from fin_gl_batch", rs -> {
                    out.add(batch("ERP_GL", "GL journal batch", rs.getString("record_ref"), rs.getString("gl_reference"),
                            rs.getString("status"), rs.getInt("journal_count"), null, null,
                            rs.getBigDecimal("debit_total"), rs.getString("created_by"),
                            time(rs.getTimestamp("created_at")), null));
                });
        jdbc.query("select b.record_ref, b.bordereau_period, b.record_count, b.total_ceded_premium, b.status, b.created_by,"
                + " b.created_at, (select max(o.ack_ref) from int_outbound_message o where o.interface_code = 'REINSURER'"
                + " and o.aggregate_ref = b.record_ref) ack from ri_bordereau b", rs -> {
                    out.add(batch("REINSURER", "Reinsurance bordereau", rs.getString("record_ref"),
                            rs.getString("bordereau_period") + (rs.getString("ack") == null ? ""
                                    : " · acknowledged " + rs.getString("ack")),
                            rs.getString("status"), rs.getInt("record_count"), null, null,
                            rs.getBigDecimal("total_ceded_premium"), rs.getString("created_by"),
                            time(rs.getTimestamp("created_at")), null));
                });
        jdbc.query("select record_ref, extract_type, as_of, record_count, control_total, checksum, run_by, run_at"
                + " from rpt_extract_run", rs -> {
                    out.add(batch("ACTUARIAL", "Data extract", rs.getString("record_ref"),
                            rs.getString("extract_type") + " as of " + rs.getDate("as_of"), "PRODUCED",
                            rs.getInt("record_count"), null, null, rs.getBigDecimal("control_total"),
                            rs.getString("run_by"), time(rs.getTimestamp("run_at")), rs.getString("checksum")));
                });
        out.sort(Comparator.comparing((Map<String, Object> m) -> (LocalDateTime) m.get("at"),
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    private static Map<String, Object> batch(String interfaceCode, String type, String ref, String external,
            String status, Integer records, Integer accepted, Integer rejected, BigDecimal controlTotal, String by,
            LocalDateTime at, String checksum) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("interfaceCode", interfaceCode);
        m.put("type", type);
        m.put("ref", ref);
        m.put("external", external);
        m.put("status", status);
        m.put("records", records);
        m.put("accepted", accepted);
        m.put("rejected", rejected);
        m.put("controlTotal", controlTotal);
        m.put("by", by);
        m.put("at", at);
        m.put("checksum", checksum);
        return m;
    }

    // ── reconciliation dashboard ─────────────────────────────────────────────────────────────────────

    /**
     * Per interface, what was sent or received against what the other side (or the next step) confirms, with the
     * difference. A non-zero difference is a break the interface's exception owner works (NS-SH12-005).
     */
    public List<Map<String, Object>> reconciliation() {
        security.authenticatedUser().validateHasPermissionTo("READ_INTEGRATION");
        List<Map<String, Object>> out = new ArrayList<>();
        for (InterfaceRegistry.Entry e : registry.entries()) {
            List<Map<String, Object>> lines = new ArrayList<>();
            switch (e.code()) {
                case "BANK_PAYMENTS" -> {
                    Object[] n = row("select count(*), coalesce(sum(amount), 0),"
                            + " sum(case when status = 'APPLIED' then 1 else 0 end),"
                            + " coalesce(sum(case when status = 'APPLIED' then amount else 0 end), 0),"
                            + " sum(case when status = 'SUSPENSE' then 1 else 0 end),"
                            + " sum(case when status = 'UNKNOWN' then 1 else 0 end)"
                            + " from int_inbound_message where message_type = 'PAYMENT_CONFIRMATION'");
                    lines.add(line("Payment notifications", "Received", n[0], "Applied to premium", n[2], "COUNT",
                            "In suspense " + num(n[4]) + "; awaiting provider status " + num(n[5])));
                    lines.add(line("Notified amount", "Received", n[1], "Applied to premium", n[3], "BWP", null));
                    Object[] s = row("select count(*), sum(case when status = 'MATCHED' or resolved_at is not null"
                            + " then 1 else 0 end) from bil_recon_item");
                    lines.add(line("Bank statement lines", "Lines", s[0], "Matched or resolved", s[1], "COUNT",
                            "Open items are owned in Finance > Reconciliation"));
                }
                case "EMAIL_WHATSAPP" -> {
                    Object[] m = row("select sum(case when status <> 'SUPPRESSED' then 1 else 0 end),"
                            + " sum(case when status in ('DELIVERED', 'READ') then 1 else 0 end),"
                            + " sum(case when status = 'FAILED' then 1 else 0 end) from com_message");
                    lines.add(line("Customer messages", "Sent to provider", m[0], "Delivered or read", m[1], "COUNT",
                            "Failed " + num(m[2]) + "; the rest await a delivery report"));
                }
                case "KYC_AML" -> {
                    Object[] k = row("select count(*), sum(case when status <> 'PENDING' then 1 else 0 end),"
                            + " sum(case when referral_status = 'OPEN' then 1 else 0 end) from int_screening_check");
                    lines.add(line("Screening checks", "Requested", k[0], "Matched to an outcome", k[1], "COUNT",
                            "Open compliance referrals " + num(k[2])));
                }
                case "EMPLOYER_LENDER" -> {
                    Object[] c = row("select coalesce(sum(total_rows), 0), coalesce(sum(accepted_rows), 0),"
                            + " coalesce(sum(rejected_rows), 0), sum(case when batch_status <> 'COMMITTED' then total_rows"
                            + " else 0 end) from grp_census_batch");
                    lines.add(line("Census rows", "Received", c[0], "Accepted", c[1], "COUNT",
                            "Rejected " + num(c[2]) + "; in batches awaiting commit " + num(c[3])));
                }
                case "ERP_GL" -> {
                    Object[] g = row("select sum(case when status <> 'PREPARED' then 1 else 0 end),"
                            + " sum(case when status in ('ACCEPTED', 'RECONCILED') then 1 else 0 end),"
                            + " coalesce(sum(case when status <> 'PREPARED' then debit_total else 0 end), 0),"
                            + " coalesce(sum(case when status = 'RECONCILED' then gl_debit_total else 0 end), 0),"
                            + " coalesce(sum(case when status = 'RECONCILED' then debit_total else 0 end), 0)"
                            + " from fin_gl_batch");
                    lines.add(line("Journal batches", "Submitted", g[0], "Accepted by the GL", g[1], "COUNT", null));
                    lines.add(line("Debits of reconciled batches", "Exported", g[4], "GL confirms", g[3], "BWP",
                            "Exported debits awaiting reconciliation: BWP "
                                    + decimal(g[2]).subtract(decimal(g[4])).toPlainString()));
                }
                case "ACTUARIAL" -> {
                    Object[] x = row("select count(*), sum(case when checksum is not null then 1 else 0 end)"
                            + " from rpt_extract_run");
                    lines.add(line("Extract runs", "Produced", x[0], "With counts, totals and checksum", x[1], "COUNT",
                            null));
                }
                case "REINSURER" -> {
                    Object[] r = row("select sum(case when status = 'SUBMITTED' then 1 else 0 end),"
                            + " coalesce(sum(case when status = 'SUBMITTED' then total_ceded_premium else 0 end), 0)"
                            + " from ri_bordereau");
                    Object[] t = row("select count(*), coalesce(sum(b.total_ceded_premium), 0) from int_outbound_message o"
                            + " join ri_bordereau b on b.record_ref = o.aggregate_ref where o.interface_code = 'REINSURER'");
                    lines.add(line("Bordereaux", "Submitted", r[0], "Transmitted and acknowledged", t[0], "COUNT", null));
                    lines.add(line("Ceded premium on bordereaux", "Submitted", r[1], "Acknowledged", t[1], "BWP", null));
                }
                default -> {
                }
            }
            if (!e.handlers().isEmpty()) {
                Object[] d = row("select count(*), sum(case when status in ('DELIVERED', 'IGNORED') then 1 else 0 end),"
                        + " sum(case when status = 'FAILED' then 1 else 0 end) from int_event_delivery where handler_name in ("
                        + String.join(",", e.handlers().stream().map(h -> "?").toList()) + ")", e.handlers().toArray());
                if ("PARTNER_WEBHOOKS".equals(e.code())) {
                    Object[] o = row("select count(*) from int_outbound_message where interface_code = ?", e.code());
                    lines.add(line("Partner notifications", "Events to send", d[0], "Transmitted", o[0], "COUNT",
                            "Dead-lettered " + num(d[2])));
                } else {
                    lines.add(line("Events consumed", "Events to consume", d[0], "Processed", d[1], "COUNT",
                            "Dead-lettered " + num(d[2])));
                }
            }
            if (lines.isEmpty()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", e.code());
            m.put("name", e.name());
            m.put("exceptionOwner", e.exceptionOwner());
            m.put("lines", lines);
            m.put("breaks", lines.stream().filter(l -> "BREAK".equals(l.get("status"))).count());
            out.add(m);
        }
        return out;
    }

    private Object[] row(String sql, Object... args) {
        return jdbc.queryForObject(sql, (rs, n) -> {
            int columns = rs.getMetaData().getColumnCount();
            Object[] values = new Object[columns];
            for (int i = 0; i < columns; i++) {
                values[i] = rs.getObject(i + 1);
            }
            return values;
        }, args);
    }

    private static Map<String, Object> line(String measure, String leftLabel, Object left, String rightLabel,
            Object right, String unit, String note) {
        BigDecimal l = decimal(left);
        BigDecimal r = decimal(right);
        BigDecimal difference = l.subtract(r);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("measure", measure);
        m.put("leftLabel", leftLabel);
        m.put("left", l);
        m.put("rightLabel", rightLabel);
        m.put("right", r);
        m.put("difference", difference);
        m.put("unit", unit);
        m.put("status", difference.signum() == 0 ? "RECONCILED" : "BREAK");
        m.put("note", note);
        return m;
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }

    private static String num(Object value) {
        return decimal(value).stripTrailingZeros().toPlainString();
    }

    private static LocalDateTime time(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        return a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
    }

    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }

    private static void min(Map<String, LocalDateTime> map, String key, LocalDateTime value) {
        if (value != null) {
            map.merge(key, value, InterfaceCatalogue::earlier);
        }
    }

    private static void max(Map<String, LocalDateTime> map, String key, LocalDateTime value) {
        if (value != null) {
            map.merge(key, value, InterfaceCatalogue::later);
        }
    }
}
