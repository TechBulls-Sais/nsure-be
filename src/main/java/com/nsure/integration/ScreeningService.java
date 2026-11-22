package com.nsure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.InvalidFieldException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.controls.StaffAlertService;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * KYC/AML screening through the interface catalogue's Identity/KYC/AML interface (spec 11). No screening provider is
 * selected yet (D06/D12), so a <b>simulated provider</b> answers: a customer whose name contains a fragment of the
 * demo watch list ({@code nsure.integration.screening.watchlist}) is a possible match, anyone else is clear. Every
 * request has its own id and is matched to exactly one outcome with an evidence reference and an expiry; while the
 * provider is unavailable the check stays PENDING and is re-sent (sweep, or when the interface is restored). A possible
 * match becomes a compliance referral that someone holding DISPOSE_SCREENING — not the requester — clears or confirms.
 * The screening informs the KYC decision; it never changes the customer's KYC status by itself.
 */
@Service
public class ScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningService.class);
    static final String PROVIDER = "Simulated screening provider";

    private final JdbcTemplate jdbc;
    private final PlatformSecurityContext security;
    private final InterfaceRegistry interfaces;
    private final EvidenceChain audit;
    private final StaffAlertService alerts;
    private final List<String> watchlist;
    private final int validMonths;

    public ScreeningService(JdbcTemplate jdbc, PlatformSecurityContext security, InterfaceRegistry interfaces,
            EvidenceChain audit, StaffAlertService alerts,
            @Value("${nsure.integration.screening.watchlist:sanctioned,watchlist,politically exposed}") String watchlist,
            @Value("${nsure.integration.screening.valid-months:12}") int validMonths) {
        this.jdbc = jdbc;
        this.security = security;
        this.interfaces = interfaces;
        this.audit = audit;
        this.alerts = alerts;
        this.watchlist = Arrays.stream(watchlist.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).toList();
        this.validMonths = validMonths;
    }

    /** {customerRef}: sends a screening request for one customer. */
    @Transactional
    public Map<String, Object> request(JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("KYC_CUSTOMER");
        String customerRef = body == null || !body.hasNonNull("customerRef") ? null
                : body.get("customerRef").asText().trim().toUpperCase(Locale.ROOT);
        if (customerRef == null || customerRef.isEmpty()) {
            throw new InvalidFieldException("customerRef", "Choose the customer to screen.");
        }
        List<String> names = jdbc.queryForList("select name from ins_customer where record_ref = ?", String.class,
                customerRef);
        if (names.isEmpty()) {
            throw new NotFoundException("Customer " + customerRef + " not found");
        }
        Integer open = jdbc.queryForObject("select count(*) from int_screening_check where customer_ref = ? and"
                + " (status = 'PENDING' or referral_status = 'OPEN')", Integer.class, customerRef);
        if (open != null && open > 0) {
            throw new ConflictException(customerRef + " already has a screening check in progress.");
        }
        Integer count = jdbc.queryForObject("select count(*) from int_screening_check", Integer.class);
        String ref = String.format("SCR-%05d", (count == null ? 0 : count) + 1);
        jdbc.update("insert into int_screening_check (record_ref, request_id, customer_ref, provider, status, attempts,"
                + " requested_by, requested_at) values (?, ?, ?, ?, 'PENDING', 0, ?, ?)", ref,
                UUID.randomUUID().toString(), customerRef, PROVIDER, user.getUsername(), LocalDateTime.now());
        send(ref, names.get(0));
        audit.record(user.getUsername(), "KYC/AML screening requested", "Customer", customerRef, ref);
        return check(ref);
    }

    /**
     * Asks the provider for one pending check. Unavailable, it stays PENDING with the attempt recorded; otherwise the
     * single outcome is stored with its evidence reference and expiry.
     */
    private void send(String ref, String name) {
        LocalDateTime now = LocalDateTime.now();
        if (!interfaces.available("KYC_AML")) {
            jdbc.update("update int_screening_check set attempts = attempts + 1, last_attempt_at = ?, outcome_detail = ?"
                    + " where record_ref = ? and status = 'PENDING'", now, "Screening provider unavailable: the check"
                    + " stays pending and is re-sent when it answers.", ref);
            return;
        }
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String hit = watchlist.stream().filter(lower::contains).findFirst().orElse(null);
        String evidence = "SIMSCR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        if (hit == null) {
            jdbc.update("update int_screening_check set status = 'CLEAR', attempts = attempts + 1, last_attempt_at = ?,"
                    + " completed_at = ?, evidence_ref = ?, expires_on = ?, outcome_detail = ? where record_ref = ? and"
                    + " status = 'PENDING'", now, now, evidence, LocalDate.now().plusMonths(validMonths),
                    "No match on the sanctions, PEP and adverse-media lists (simulated provider).", ref);
        } else {
            jdbc.update("update int_screening_check set status = 'REFERRED', attempts = attempts + 1,"
                    + " last_attempt_at = ?, completed_at = ?, evidence_ref = ?, expires_on = ?, outcome_detail = ?,"
                    + " referral_status = 'OPEN', referral_owner = 'Compliance' where record_ref = ? and status = 'PENDING'",
                    now, now, evidence, LocalDate.now().plusMonths(validMonths), "Possible match: the name contains"
                            + " watch-list entry '" + hit + "' (simulated provider). Compliance decides.", ref);
            alerts.raise("SCREENING_REFERRAL", "DISPOSE_SCREENING", "Screening referral " + ref,
                    "A KYC/AML screening returned a possible match. Clear it or confirm the match in the Integration"
                            + " monitor (KYC/AML screening).", "screening-referral:" + ref, "system");
        }
    }

    /** Re-sends every pending check (sweep, or the interface was restored). Returns how many were answered. */
    public int retryPending() {
        int answered = 0;
        for (Map<String, Object> row : jdbc.queryForList("select s.record_ref ref, c.name name from int_screening_check s"
                + " join ins_customer c on c.record_ref = s.customer_ref where s.status = 'PENDING' order by s.id")) {
            String ref = String.valueOf(value(row, "ref"));
            send(ref, (String) value(row, "name"));
            String status = jdbc.queryForObject("select status from int_screening_check where record_ref = ?",
                    String.class, ref);
            if (!"PENDING".equals(status)) {
                answered++;
            }
        }
        return answered;
    }

    /** Staff retry of pending checks. */
    public Map<String, Object> retryNow() {
        security.authenticatedUser().validateHasPermissionTo("KYC_CUSTOMER");
        return Map.of("answered", retryPending());
    }

    @Scheduled(fixedDelayString = "${nsure.integration.screening.retry-ms:900000}",
            initialDelayString = "${nsure.integration.screening.retry-ms:900000}")
    public void scheduledRetry() {
        try {
            retryPending();
        } catch (RuntimeException ex) {
            log.warn("Screening retry failed", ex);
        }
    }

    /**
     * {outcome CLEARED | CONFIRMED_MATCH, note}: Compliance disposes of a referral. The requester cannot dispose of
     * their own request; the disposition is audit evidence.
     */
    @Transactional
    public Map<String, Object> dispose(String ref, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("DISPOSE_SCREENING");
        Map<String, Object> row = jdbc.queryForList("select requested_by, referral_status, customer_ref from"
                + " int_screening_check where record_ref = ?", ref).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Screening check " + ref + " not found"));
        if (!"OPEN".equals(value(row, "referral_status"))) {
            throw new ConflictException(ref + " has no open referral.");
        }
        if (user.getUsername().equalsIgnoreCase(String.valueOf(value(row, "requested_by")))) {
            throw new NoAuthorizationException("You requested this screening, so someone else must decide the referral.");
        }
        String outcome = body == null || !body.hasNonNull("outcome") ? "" : body.get("outcome").asText()
                .toUpperCase(Locale.ROOT);
        String note = body == null || !body.hasNonNull("note") ? "" : body.get("note").asText().trim();
        if (!"CLEARED".equals(outcome) && !"CONFIRMED_MATCH".equals(outcome)) {
            throw new InvalidFieldException("outcome", "outcome must be CLEARED or CONFIRMED_MATCH.");
        }
        if (note.length() < 10) {
            throw new InvalidFieldException("note", "Record the basis of the decision (at least 10 characters).");
        }
        jdbc.update("update int_screening_check set referral_status = ?, status = ?, disposed_by = ?, disposed_at = ?,"
                + " disposition_note = ? where record_ref = ?", outcome, "CLEARED".equals(outcome) ? "CLEAR" : "MATCH",
                user.getUsername(), LocalDateTime.now(), note, ref);
        audit.record(user.getUsername(), "Screening referral " + outcome.toLowerCase(Locale.ROOT).replace('_', ' '),
                "Customer", String.valueOf(value(row, "customer_ref")), ref + ": " + note);
        return check(ref);
    }

    /** Every check, newest first; an expired clear result shows as EXPIRED. READ_CUSTOMER or READ_INTEGRATION. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> checks() {
        AppUser user = security.authenticatedUser();
        if (!user.hasPermissionTo("READ_CUSTOMER") && !user.hasPermissionTo("READ_INTEGRATION")) {
            user.validateHasPermissionTo("READ_CUSTOMER");
        }
        return jdbc.query("select s.*, c.name customer_name from int_screening_check s left join ins_customer c on"
                + " c.record_ref = s.customer_ref order by s.id desc", (rs, n) -> view(rs));
    }

    private Map<String, Object> check(String ref) {
        return jdbc.queryForObject("select s.*, c.name customer_name from int_screening_check s left join ins_customer c"
                + " on c.record_ref = s.customer_ref where s.record_ref = ?", (rs, n) -> view(rs), ref);
    }

    private static Map<String, Object> view(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        LocalDate expires = rs.getDate("expires_on") == null ? null : rs.getDate("expires_on").toLocalDate();
        String status = rs.getString("status");
        m.put("ref", rs.getString("record_ref"));
        m.put("requestId", rs.getString("request_id"));
        m.put("customerRef", rs.getString("customer_ref"));
        m.put("customerName", rs.getString("customer_name"));
        m.put("provider", rs.getString("provider"));
        m.put("status", "CLEAR".equals(status) && expires != null && expires.isBefore(LocalDate.now()) ? "EXPIRED"
                : status);
        m.put("detail", rs.getString("outcome_detail"));
        m.put("evidenceRef", rs.getString("evidence_ref"));
        m.put("expiresOn", expires);
        m.put("attempts", rs.getInt("attempts"));
        m.put("lastAttemptAt", time(rs.getTimestamp("last_attempt_at")));
        m.put("requestedBy", rs.getString("requested_by"));
        m.put("requestedAt", time(rs.getTimestamp("requested_at")));
        m.put("completedAt", time(rs.getTimestamp("completed_at")));
        m.put("referralStatus", rs.getString("referral_status"));
        m.put("referralOwner", rs.getString("referral_owner"));
        m.put("disposedBy", rs.getString("disposed_by"));
        m.put("dispositionNote", rs.getString("disposition_note"));
        return m;
    }

    private static LocalDateTime time(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
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
