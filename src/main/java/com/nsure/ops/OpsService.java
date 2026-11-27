package com.nsure.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.grouplife.GroupLifeService;
import com.nsure.portfolio.DocumentIntake;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operations around the transaction modules:
 * <ul>
 *   <li>Partner portals (specification section 04): an agent, employer or lender login sees only the one
 *       intermediary, scheme or lender it is tied to, and never medical evidence. Requests it raises get a reference
 *       and land in the operations queue.</li>
 *   <li>Workflow (SH10): a definition per kind of work with its team, steps, priority and SLA, and the business
 *       calendar SLA due times are counted in. The queue across the modules is {@link WorkQueueService}.</li>
 *   <li>Documents (SH09A): one register of policy and claim documents with scan status, retention date, legal hold
 *       and search. A held document is never eligible for disposal.</li>
 *   <li>Payouts: hospital cash, credit claim and contractual benefit payouts in one list beside the claims register.</li>
 * </ul>
 */
@Service
public class OpsService {

    private static final RowMapper<Map<String, Object>> ROW = OpsService::row;

    private final JdbcTemplate jdbc;
    private final PlatformSecurityContext security;
    private final EvidenceChain audit;
    private final GroupLifeService groupLife;

    public OpsService(JdbcTemplate jdbc, PlatformSecurityContext security, EvidenceChain audit,
            GroupLifeService groupLife) {
        this.jdbc = jdbc;
        this.security = security;
        this.audit = audit;
        this.groupLife = groupLife;
    }

    // ── partner portals ───────────────────────────────────────────────────────────────────────────

    /** Everything the signed-in partner may see: its own portfolio, scheme or loans, and its requests. */
    @Transactional(readOnly = true)
    public Map<String, Object> portal() {
        AppUser user = user("USE_PARTNER_PORTAL");
        Map<String, Object> link = partnerAccount(user);
        String type = (String) link.get("portalType");
        String scope = (String) link.get("scopeRef");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("portalType", type);
        out.put("scopeRef", scope);
        switch (type) {
            case "AGENT" -> {
                out.put("partner", first("select record_ref, name, intermediary_type, status, licence_number from"
                        + " dist_intermediary where record_ref = ?", scope));
                out.put("applications", jdbc.query("select record_ref, customer_name, product, amount, premium, status"
                        + " from ins_application where intermediary_code = ? order by id desc", ROW, scope));
                // NS-IL02-002, AT-IL02-03: the agent follows the evidence asked on their applications (what, status,
                // due date); restricted clinical evidence is named only as such and no evidence detail is ever shown.
                out.put("evidenceRequests", jdbc.query("select e.id, c.application_ref, case when e.restricted = TRUE then"
                        + " 'Restricted medical evidence' else e.requirement end as requirement, e.restricted, e.status,"
                        + " e.due_date, e.reminder_count from uw_evidence e join uw_case c on c.record_ref = e.case_ref"
                        + " join ins_application a on a.record_ref = c.application_ref where a.intermediary_code = ?"
                        + " order by e.due_date", ROW, scope));
                // The agent's book: policies assigned to them, and policies issued from applications they sold.
                // AT-SH06-03: access follows the assignment in force today (a future-dated transfer moves it on its
                // date), and a policy sold by this agent but now assigned to someone else is no longer shown.
                java.sql.Date today = java.sql.Date.valueOf(java.time.LocalDate.now());
                out.put("policies", jdbc.query("select p.record_ref, p.customer_name, p.product, p.amount, p.premium,"
                        + " p.status from ins_policy p where p.record_ref in (select a.policy_ref from"
                        + " dist_policy_assignment a join dist_intermediary i on i.id = a.intermediary_id where"
                        + " i.record_ref = ? and a.status = 'APPLIED' and a.effective_from <= ?"
                        + " and (a.ended_on is null or a.ended_on > ?)) or (p.application_ref in (select ap.record_ref"
                        + " from ins_application ap where ap.intermediary_code = ?) and not exists (select 1 from"
                        + " dist_policy_assignment b join dist_intermediary j on j.id = b.intermediary_id where"
                        + " b.policy_ref = p.record_ref and b.status = 'APPLIED' and j.record_ref <> ?"
                        + " and b.effective_from <= ? and (b.ended_on is null or b.ended_on > ?))) order by p.id desc",
                        ROW, scope, today, today, scope, scope, today, today));
                out.put("statements", jdbc.query("select s.record_ref, s.net_amount, s.line_count, s.status, s.issued_at,"
                        + " s.paid_at from dist_statement s join dist_intermediary i on i.id = s.intermediary_id where"
                        + " i.record_ref = ? order by s.id desc", ROW, scope));
                out.put("commissions", jdbc.query("select c.record_ref, c.policy_ref, c.product, c.line_kind, c.amount,"
                        + " c.status, c.created_at from dist_commission c join dist_intermediary i on i.id ="
                        + " c.intermediary_id where i.record_ref = ? order by c.id desc", ROW, scope));
            }
            case "EMPLOYER" -> {
                out.put("partner", first("select record_ref, name, sector, scheme_status as status, renewal, policy_year from ins_scheme"
                        + " where record_ref = ?", scope));
                // NS-GL03-003: the employer sees who is covered and for how much, never why underwriting decided.
                out.put("members", jdbc.query("select m.member_key, m.first_name, m.last_name, m.member_status,"
                        + " m.join_date, m.accepted_cover, m.pending_cover from grp_member m join ins_scheme s on"
                        + " s.id = m.scheme_id where s.record_ref = ? order by m.last_name, m.first_name", ROW, scope));
                out.put("invoices", jdbc.query("select i.record_ref, i.billing_period, i.amount, i.paid_amount,"
                        + " i.outstanding, i.invoice_status, i.member_count from grp_invoice i join ins_scheme s on"
                        + " s.id = i.scheme_id where s.record_ref = ? order by i.id desc", ROW, scope));
                // Certificates of the sponsor's own scheme only: accepted cover, with pending cover shown as pending.
                out.put("certificates", groupLife.sponsorCertificates(scope));
            }
            default -> {
                out.put("partner", first("select record_ref, name, contact_email from crd_lender where record_ref = ?",
                        scope));
                out.put("loans", jdbc.query("select record_ref, lender_loan_id, borrower_name, principal, term_months,"
                        + " balance, balance_as_of, status, disbursement_date, policy_ref, balance_exception from crd_loan"
                        + " where lender_ref = ? order by id desc", ROW, scope));
                out.put("refunds", jdbc.query("select r.record_ref, r.loan_ref, r.borrower_name, r.amount, r.status from"
                        + " crd_refund r join crd_loan l on l.record_ref = r.loan_ref where l.lender_ref = ? order by"
                        + " r.id desc", ROW, scope));
            }
        }
        out.put("requests", jdbc.query("select record_ref, request_type, detail, status, created_at, handled_by,"
                + " handled_note, result, result_ref, processed_at from portal_request where created_by = ? order by id"
                + " desc", ROW, user.getUsername()));
        if ("AGENT".equals(type)) {
            // Spec 04 agent workspace: commission statements and the arrears tasks of the agent's book.
            out.put("statements", jdbc.query("select s.record_ref, s.net_amount, s.line_count, s.status, s.issued_at,"
                    + " s.paid_at from dist_statement s join dist_intermediary i on i.id = s.intermediary_id where"
                    + " i.record_ref = ? order by s.id desc", ROW, scope));
            out.put("arrears", jdbc.query("select p.record_ref, p.customer_name, p.product, p.status, p.premium from"
                    + " ins_policy p where (p.record_ref in (select a.policy_ref from dist_policy_assignment a join"
                    + " dist_intermediary i on i.id = a.intermediary_id where i.record_ref = ? and a.ended_on is null) or"
                    + " p.application_ref in (select ap.record_ref from ins_application ap where ap.intermediary_code ="
                    + " ?)) and p.status not in ('Active', 'Grace', 'Cancelled', 'Terminated', 'Matured') order by p.id desc", ROW,
                    scope, scope));
        }
        return out;
    }

    /**
     * The partner a login may act for, checked on every request (AT-SH11-02, AT-SH01-03, NS-SH11-002): the portal link
     * must not have passed its end date, and the partner itself must still be in good standing: an intermediary
     * ACTIVE with a current appointment, a scheme not closed, a lender active. Suspending the partner closes the
     * portal at once, for sessions already open as well.
     */
    public Map<String, Object> partnerAccount(AppUser user) {
        Map<String, Object> account = first("select portal_type, scope_ref, valid_until from portal_account where"
                + " username = ?", user.getUsername());
        if (account == null) {
            throw new NoAuthorizationException("This login is not linked to an agent, employer or lender.");
        }
        Object until = account.get("validUntil");
        if (until instanceof LocalDate end && end.isBefore(LocalDate.now())) {
            throw new NoAuthorizationException("Your portal access ended on " + end + ".");
        }
        String scope = (String) account.get("scopeRef");
        switch (String.valueOf(account.get("portalType"))) {
            case "AGENT" -> {
                Map<String, Object> i = first("select status, appointment_expiry from dist_intermediary where record_ref"
                        + " = ?", scope);
                if (i == null || !"ACTIVE".equals(i.get("status"))) {
                    throw new NoAuthorizationException("Intermediary " + scope + " is "
                            + (i == null ? "not on record" : i.get("status")) + ", so its portal access is closed.");
                }
                if (i.get("appointmentExpiry") instanceof LocalDate expiry && expiry.isBefore(LocalDate.now())) {
                    throw new NoAuthorizationException("Intermediary " + scope + "'s appointment expired on " + expiry
                            + ", so its portal access is closed.");
                }
            }
            case "EMPLOYER" -> {
                Map<String, Object> scheme = first("select scheme_status from ins_scheme where record_ref = ?", scope);
                if (scheme == null || "CLOSED".equals(scheme.get("schemeStatus"))) {
                    throw new NoAuthorizationException("Scheme " + scope + " is closed, so its portal access is closed.");
                }
            }
            default -> {
                Map<String, Object> lender = first("select active from crd_lender where record_ref = ?", scope);
                Object active = lender == null ? null : lender.get("active");
                if (lender == null || Boolean.FALSE.equals(active) || Integer.valueOf(0).equals(active)) {
                    throw new NoAuthorizationException("Lender " + scope + " is not active, so its portal access is"
                            + " closed.");
                }
            }
        }
        return account;
    }

    /** {requestType, detail}: a partner's request, acknowledged with a reference (NS-UX-005). */
    @Transactional
    public Map<String, Object> portalRequest(JsonNode body) {
        AppUser user = user("USE_PARTNER_PORTAL");
        Map<String, Object> account = partnerAccount(user);
        String type = required(body, "requestType").toUpperCase(Locale.ROOT);
        String detail = required(body, "detail");
        Integer n = jdbc.queryForObject("select count(*) from portal_request", Integer.class);
        String ref = String.format("PRQ-%05d", (n == null ? 0 : n) + 1);
        jdbc.update("insert into portal_request (record_ref, portal_type, scope_ref, request_type, detail, status,"
                + " created_by, created_at) values (?, ?, ?, ?, ?, 'OPEN', ?, ?)", ref, account.get("portalType"),
                account.get("scopeRef"), type, detail, user.getUsername(), LocalDateTime.now());
        return Map.of("ref", ref, "status", "OPEN");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> portalRequests() {
        staff();
        return jdbc.query("select * from portal_request order by id desc", ROW);
    }

    /** {note}: staff close a partner's request once it has been actioned in the owning module. */
    @Transactional
    public Map<String, Object> closePortalRequest(String ref, JsonNode body) {
        AppUser user = staff();
        if (jdbc.update("update portal_request set status = 'DONE', handled_by = ?, handled_note = ? where record_ref = ?"
                + " and status = 'OPEN'", user.getUsername(), required(body, "note"), ref) == 0) {
            throw new ConflictException(ref + " is not open.");
        }
        return Map.of("ref", ref, "status", "DONE");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> portalAccounts() {
        user("READ_USER");
        return jdbc.query("select * from portal_account order by username", ROW);
    }

    /** {username, portalType, scopeRef}: ties a login to the one partner it may see. */
    @Transactional
    public List<Map<String, Object>> savePortalAccount(JsonNode body) {
        AppUser user = user("UPDATE_USER");
        String username = required(body, "username");
        String type = required(body, "portalType").toUpperCase(Locale.ROOT);
        if (!Set.of("AGENT", "EMPLOYER", "LENDER").contains(type)) {
            throw new IllegalArgumentException("portalType must be AGENT, EMPLOYER or LENDER.");
        }
        String until = textOr(body, "validUntil", null);
        LocalDate end = null;
        if (until != null) {
            try {
                end = LocalDate.parse(until);
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("validUntil is a date like 2027-12-31.");
            }
        }
        jdbc.update("delete from portal_account where username = ?", username);
        jdbc.update("insert into portal_account (username, portal_type, scope_ref, valid_until) values (?, ?, ?, ?)",
                username, type, required(body, "scopeRef"), end);
        audit.record(user.getUsername(), "Portal access set", "User", username, type + " " + required(body, "scopeRef")
                + (end == null ? "" : " until " + end));
        return jdbc.query("select * from portal_account order by username", ROW);
    }

    /** {reason}: removes a login's portal link; it takes effect on the login's next request (AT-SH11-02). */
    @Transactional
    public List<Map<String, Object>> removePortalAccount(String username, JsonNode body) {
        AppUser user = user("UPDATE_USER");
        String reason = required(body, "reason");
        if (jdbc.update("delete from portal_account where username = ?", username) == 0) {
            throw new NotFoundException("No portal access for " + username);
        }
        audit.record(user.getUsername(), "Portal access removed", "User", username, reason);
        return jdbc.query("select * from portal_account order by username", ROW);
    }

    // ── workflow: definitions, calendar and the queue (NS-SH10-001/002/005) ───────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> workflow() {
        staff();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("definitions", jdbc.query("select * from wf_definition order by name", ROW));
        out.put("calendar", first("select * from wf_calendar where id = 1"));
        out.put("versions", jdbc.query("select * from wf_definition_version order by code, version_no desc", ROW));
        return out;
    }

    /**
     * {name?, team?, slaHours?, steps?, priority?}: changes a definition; the version moves on so older work is
     * traceable. The queue itself is {@link WorkQueueService}.
     */
    @Transactional
    public Map<String, Object> saveDefinition(String code, JsonNode body) {
        AppUser user = user("MANAGE_CONFIG");
        Map<String, Object> d = first("select * from wf_definition where code = ?", code);
        if (d == null) {
            throw new NotFoundException("Workflow " + code + " not found");
        }
        int sla = body.path("slaHours").asInt((Integer) d.get("slaHours"));
        if (sla < 1 || sla > 2000) {
            throw new IllegalArgumentException("The SLA must be between 1 and 2000 working hours.");
        }
        String priority = textOr(body, "priority", (String) d.get("priority")).toUpperCase(Locale.ROOT);
        if (!Set.of("HIGH", "NORMAL", "LOW").contains(priority)) {
            throw new IllegalArgumentException("Priority must be HIGH, NORMAL or LOW.");
        }
        java.math.BigDecimal limit = body.hasNonNull("authorityLimit") && !body.get("authorityLimit").asText().isBlank()
                ? new java.math.BigDecimal(body.get("authorityLimit").asText().trim())
                : body.has("authorityLimit") ? null : (java.math.BigDecimal) d.get("authorityLimit");
        if (limit != null && limit.signum() < 0) {
            throw new IllegalArgumentException("The authority limit cannot be negative.");
        }
        String roles = textOr(body, "roles", (String) d.get("roles"));
        if (roles != null) {
            for (String code2 : roles.split(",")) {
                if (!code2.isBlank() && !com.nsure.security.domain.PermissionCatalog.isKnown(code2.trim())) {
                    throw new IllegalArgumentException("Unknown permission code " + code2.trim() + " in roles.");
                }
            }
        }
        // NS-SH10-001: the version moves on and the new version is kept beside the earlier ones; running service
        // cases keep the version (and SLA) they were opened under.
        jdbc.update("update wf_definition set name = ?, team = ?, sla_hours = ?, steps = ?, priority = ?,"
                + " entry_criteria = ?, roles = ?, authority_limit = ?, required_evidence = ?,"
                + " version_no = version_no + 1, updated_by = ?, updated_at = ? where code = ?",
                textOr(body, "name", (String) d.get("name")), textOr(body, "team", (String) d.get("team")), sla,
                textOr(body, "steps", (String) d.get("steps")), priority,
                textOr(body, "entryCriteria", (String) d.get("entryCriteria")), roles, limit,
                textOr(body, "requiredEvidence", (String) d.get("requiredEvidence")), user.getUsername(),
                LocalDateTime.now(), code);
        jdbc.update("insert into wf_definition_version (code, version_no, name, team, sla_hours, priority, steps,"
                + " entry_criteria, roles, authority_limit, required_evidence, changed_by, changed_at) select code,"
                + " version_no, name, team, sla_hours, priority, steps, entry_criteria, roles, authority_limit,"
                + " required_evidence, updated_by, updated_at from wf_definition where code = ?", code);
        audit.record(user.getUsername(), "Workflow definition changed", "Workflow", code,
                "SLA " + sla + "h, priority " + priority);
        return workflow();
    }

    /** {workStartHour, workEndHour, workingDays, holidays}: the business calendar SLA times are counted in. */
    @Transactional
    public Map<String, Object> saveCalendar(JsonNode body) {
        AppUser user = user("MANAGE_CONFIG");
        int start = body.path("workStartHour").asInt(8);
        int end = body.path("workEndHour").asInt(17);
        if (start < 0 || end > 24 || start >= end) {
            throw new IllegalArgumentException("Working hours must satisfy 0 ≤ start < end ≤ 24.");
        }
        jdbc.update("update wf_calendar set work_start_hour = ?, work_end_hour = ?, working_days = ?, holidays = ?"
                + " where id = 1", start, end, required(body, "workingDays").toUpperCase(Locale.ROOT),
                textOr(body, "holidays", ""));
        audit.record(user.getUsername(), "Business calendar changed", "Calendar", "1", null);
        return workflow();
    }

    /**
     * Adds working minutes to a start time, skipping nights, non-working days and holidays. The remainder is kept as an
     * exact duration: counting it in whole minutes would leave a start time's seconds (11:32:52 → 16:59:52) as a
     * sub-minute gap before the close that never gets used, so the due time would stop at the end of the first day.
     */
    public static LocalDateTime addWorkingMinutes(LocalDateTime start, long minutes, Map<String, Object> calendar) {
        int open = (Integer) calendar.get("workStartHour");
        int close = (Integer) calendar.get("workEndHour");
        Set<DayOfWeek> days = workingDays(calendar);
        Set<String> holidays = holidays(calendar);
        LocalDateTime t = start;
        java.time.Duration left = java.time.Duration.ofMinutes(minutes);
        int guard = 0;
        while (left.isPositive() && guard++ < 5000) {
            LocalDate day = t.toLocalDate();
            boolean working = days.contains(day.getDayOfWeek()) && !holidays.contains(day.toString());
            LocalDateTime dayOpen = day.atTime(open, 0);
            LocalDateTime dayClose = close == 24 ? day.plusDays(1).atStartOfDay() : day.atTime(close, 0);
            if (!working || !t.isBefore(dayClose)) {
                t = day.plusDays(1).atTime(open, 0);
                continue;
            }
            if (t.isBefore(dayOpen)) {
                t = dayOpen;
            }
            java.time.Duration available = java.time.Duration.between(t, dayClose);
            java.time.Duration used = available.compareTo(left) < 0 ? available : left;
            t = t.plus(used);
            left = left.minus(used);
        }
        return t;
    }

    /** Working minutes between two times under the business calendar (NS-SH10-005: working time beside elapsed). */
    public static long workingMinutesBetween(LocalDateTime from, LocalDateTime to, Map<String, Object> calendar) {
        if (from == null || to == null || !to.isAfter(from)) {
            return 0;
        }
        int open = (Integer) calendar.get("workStartHour");
        int close = (Integer) calendar.get("workEndHour");
        Set<DayOfWeek> days = workingDays(calendar);
        Set<String> holidays = holidays(calendar);
        long total = 0;
        LocalDate day = from.toLocalDate();
        int guard = 0;
        while (!day.isAfter(to.toLocalDate()) && guard++ < 5000) {
            if (days.contains(day.getDayOfWeek()) && !holidays.contains(day.toString())) {
                LocalDateTime dayOpen = day.atTime(open, 0);
                LocalDateTime dayClose = close == 24 ? day.plusDays(1).atStartOfDay() : day.atTime(close, 0);
                LocalDateTime a = from.isAfter(dayOpen) ? from : dayOpen;
                LocalDateTime b = to.isBefore(dayClose) ? to : dayClose;
                if (b.isAfter(a)) {
                    total += java.time.Duration.between(a, b).toMinutes();
                }
            }
            day = day.plusDays(1);
        }
        return total;
    }

    private static Set<DayOfWeek> workingDays(Map<String, Object> calendar) {
        return Arrays.stream(String.valueOf(calendar.get("workingDays")).split(","))
                .map(d -> switch (d.trim()) {
                    case "MON" -> DayOfWeek.MONDAY;
                    case "TUE" -> DayOfWeek.TUESDAY;
                    case "WED" -> DayOfWeek.WEDNESDAY;
                    case "THU" -> DayOfWeek.THURSDAY;
                    case "FRI" -> DayOfWeek.FRIDAY;
                    case "SAT" -> DayOfWeek.SATURDAY;
                    default -> DayOfWeek.SUNDAY;
                }).collect(Collectors.toSet());
    }

    private static Set<String> holidays(Map<String, Object> calendar) {
        return calendar.get("holidays") == null ? Set.of()
                : Arrays.stream(String.valueOf(calendar.get("holidays")).split(",")).map(String::trim)
                        .collect(Collectors.toSet());
    }

    /** The business calendar as a map (workStartHour, workEndHour, workingDays, holidays), for SLA arithmetic. */
    public Map<String, Object> calendar() {
        return first("select * from wf_calendar where id = 1");
    }

    /** Adds working hours to a start time, skipping nights, non-working days and holidays. */
    public static LocalDateTime addWorkingHours(LocalDateTime start, int hours, Map<String, Object> calendar) {
        return addWorkingMinutes(start, hours * 60L, calendar);
    }

    // ── documents: register, scan status, retention, legal hold, search (NS-SH09A-004..006) ───────

    /**
     * The document register (NS-SH09A-005): policy documents for READ_POLICY holders, claim evidence (and uploads held
     * in quarantine) for READ_CLAIM holders, so a search never shows a document its owning record would not. Indexed by
     * customer (party), owning policy or claim, document class and date. {@code q} matches the file name, type, owner
     * and customer, and the text of released and generated PDFs. Every search is logged as sensitive access.
     */
    @Transactional
    public Map<String, Object> documents(String q, String customerRef, String ownerRef, String documentClass,
            LocalDate from, LocalDate to) {
        AppUser user = security.authenticatedUser();
        boolean policies = user.hasPermissionTo("READ_POLICY");
        boolean claims = user.hasPermissionTo("READ_CLAIM");
        if (!policies && !claims) {
            throw new NoAuthorizationException("User has no authority to read policy or claim documents");
        }
        Map<String, Integer> retention = new LinkedHashMap<>();
        jdbc.query("select document_class, retain_years from doc_retention", rs -> {
            retention.put(rs.getString(1), rs.getInt(2));
        });
        Map<String, Map<String, Object>> controls = jdbc.query("select * from doc_control", ROW).stream()
                .collect(Collectors.toMap(c -> (String) c.get("docKey"), c -> c));
        String term = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> docs = new ArrayList<>();
        if (policies) {
            Set<Long> inText = contentMatches("ins_policy_document", term);
            for (Map<String, Object> d : jdbc.query("select d.id, d.document_type, d.file_name, d.status, d.uploaded_at,"
                    + " d.uploaded_by, p.record_ref as owner_ref, p.customer_ref, p.customer_name from ins_policy_document d"
                    + " join ins_policy p on p.id = d.policy_id", ROW)) {
                d.put("docKey", "P:" + d.get("id"));
                d.put("documentClass", "Policy document");
                d.put("ownerKind", "POLICY");
                d.put("contentMatch", inText.contains(((Number) d.get("id")).longValue()));
                docs.add(d);
            }
        }
        if (claims) {
            Set<Long> inText = contentMatches("ins_claim_document", term);
            for (Map<String, Object> d : jdbc.query("select d.id, d.document_type, d.file_name, d.status, d.uploaded_at,"
                    + " d.uploaded_by, d.scan_status, c.record_ref as owner_ref, c.customer_ref, c.customer_name"
                    + " from ins_claim_document d join ins_claim c on c.id = d.claim_id", ROW)) {
                d.put("docKey", "C:" + d.get("id"));
                d.put("documentClass", "Claim evidence");
                d.put("ownerKind", "CLAIM");
                d.put("contentMatch", inText.contains(((Number) d.get("id")).longValue()));
                docs.add(d);
            }
            // Uploads still in quarantine, or refused: on record (and under retention) but not documents of the claim,
            // and never indexed for content.
            for (Map<String, Object> d : jdbc.query("select q.id, q.document_type, q.file_name, q.status, q.uploaded_at,"
                    + " q.uploaded_by, q.owner_ref, c.customer_ref, c.customer_name from doc_quarantine q join ins_claim c"
                    + " on c.id = q.owner_id where q.owner_kind = 'CLAIM' and q.status <> 'RELEASED'", ROW)) {
                d.put("docKey", "U:" + d.get("id"));
                d.put("documentClass", "Claim evidence");
                d.put("ownerKind", "CLAIM");
                d.put("scanStatus", "PENDING".equals(d.get("status")) ? DocumentIntake.QUARANTINED : DocumentIntake.REJECTED);
                d.put("status", "PENDING".equals(d.get("status")) ? DocumentIntake.QUARANTINED : DocumentIntake.REJECTED);
                d.put("contentMatch", false);
                docs.add(d);
            }
        }
        String party = blankToNull(customerRef);
        String owner = blankToNull(ownerRef);
        String docClass = blankToNull(documentClass);
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : docs) {
            boolean details = (d.get("fileName") + " " + d.get("documentType") + " " + d.get("ownerRef") + " "
                    + d.get("customerRef") + " " + d.get("customerName")).toLowerCase(Locale.ROOT).contains(term);
            if (!term.isEmpty() && !details && !Boolean.TRUE.equals(d.get("contentMatch"))) {
                continue;
            }
            LocalDate stored = ((LocalDateTime) d.get("uploadedAt")).toLocalDate();
            if ((party != null && !party.equalsIgnoreCase(String.valueOf(d.get("customerRef"))))
                    || (owner != null && !owner.equalsIgnoreCase(String.valueOf(d.get("ownerRef"))))
                    || (docClass != null && !docClass.equals(d.get("documentClass")))
                    || (from != null && stored.isBefore(from)) || (to != null && stored.isAfter(to))) {
                continue;
            }
            d.put("contentMatch", !term.isEmpty() && !details && Boolean.TRUE.equals(d.get("contentMatch")));
            Map<String, Object> control = controls.get((String) d.get("docKey"));
            boolean hold = control != null && Boolean.TRUE.equals(control.get("legalHold"));
            LocalDate retainUntil = stored.plusYears(retention.getOrDefault((String) d.get("documentClass"), 7));
            // No malware scanner is connected: an upload is called clean only if a scanner said so (AT-SH09A-02).
            String scan = d.get("scanStatus") == null ? DocumentIntake.GENERATED : (String) d.get("scanStatus");
            d.put("scanStatus", scan);
            d.put("scanLabel", DocumentIntake.scanLabel(scan));
            d.put("classification", DocumentIntake.classification((String) d.get("documentType")));
            d.put("retentionPolicy", d.get("documentClass") + ": "
                    + retention.getOrDefault((String) d.get("documentClass"), 7) + " years from storage");
            d.put("retainUntil", retainUntil);
            d.put("legalHold", hold);
            d.put("holdReason", control == null ? null : control.get("holdReason"));
            d.put("disposalEligible", !hold && retainUntil.isBefore(today));
            out.add(d);
        }
        out.sort(Comparator.comparing((Map<String, Object> d) -> (LocalDateTime) d.get("uploadedAt")).reversed());
        // A register search reaches many customers' records at once: sensitive access (NS-SH09A-005, NS-SH11-005).
        List<String> filters = new ArrayList<>();
        if (!term.isEmpty()) {
            filters.add("text \"" + term + "\"");
        }
        if (party != null) {
            filters.add("customer " + party);
        }
        if (owner != null) {
            filters.add("record " + owner);
        }
        if (docClass != null) {
            filters.add("class " + docClass);
        }
        if (from != null || to != null) {
            filters.add("stored " + (from == null ? "…" : from) + " to " + (to == null ? "…" : to));
        }
        audit.record(user.getUsername(), "Document register searched", "Document register",
                policies && claims ? "policies and claims" : policies ? "policies" : "claims",
                (filters.isEmpty() ? "no filter" : String.join(", ", filters)) + "; " + out.size() + " result(s)");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documents", out);
        result.put("retention", retention);
        result.put("classes", policies && claims ? List.of("Policy document", "Claim evidence")
                : policies ? List.of("Policy document") : List.of("Claim evidence"));
        return result;
    }

    /** Ids of the documents in the table whose indexed text contains the term (stored lower-cased). */
    private Set<Long> contentMatches(String table, String term) {
        if (term.length() < 3) {
            return Set.of();
        }
        // '!' escapes the wildcards: a backslash would itself need escaping in a MySQL string literal.
        String like = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return jdbc.queryForList("select id from " + table + " where search_text like ? escape '!'", Long.class, like)
                .stream().collect(Collectors.toSet());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** {hold, reason}: places or lifts a legal hold. A held document is never eligible for disposal. */
    @Transactional
    public Map<String, Object> legalHold(String docKey, JsonNode body) {
        AppUser user = user("UPDATE_POLICY");
        if (!docKey.startsWith("P:")) {
            // Claim evidence is under the claim's permission, as in the register (NS-SH09A-005).
            user.validateHasPermissionTo("READ_CLAIM");
        }
        boolean hold = body.path("hold").asBoolean(false);
        String reason = hold ? required(body, "reason") : null;
        jdbc.update("delete from doc_control where doc_key = ?", docKey);
        jdbc.update("insert into doc_control (doc_key, legal_hold, hold_reason, held_by, held_at) values (?, ?, ?, ?, ?)",
                docKey, hold, reason, user.getUsername(), LocalDateTime.now());
        audit.record(user.getUsername(), hold ? "Legal hold placed" : "Legal hold lifted", "Document", docKey, reason);
        return Map.of("docKey", docKey, "legalHold", hold);
    }

    // ── approved disposal (NS-SH09A-006) ─────────────────────────────────────────────────────────

    @org.springframework.beans.factory.annotation.Autowired
    private com.nsure.portfolio.DocumentFiles documentFiles;

    /** Disposal requests, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> disposals() {
        staff();
        return jdbc.query("select * from doc_disposal order by id desc", ROW);
    }

    /**
     * {docKey, reason}: asks for a policy document or claim evidence to be disposed of. Only a document past its
     * retention date and not on legal hold qualifies; nothing happens until someone else approves.
     */
    @Transactional
    public Map<String, Object> requestDisposal(JsonNode body) {
        AppUser user = user("MANAGE_CONFIG");
        String key = required(body, "docKey");
        String reason = required(body, "reason");
        if (reason.length() < 10) {
            throw new IllegalArgumentException("Give the reason for disposal (at least 10 characters).");
        }
        Map<String, Object> doc = disposable(key);
        Integer open = jdbc.queryForObject("select count(*) from doc_disposal where doc_key = ? and status = 'PENDING'",
                Integer.class, key);
        if (open != null && open > 0) {
            throw new ConflictException("A disposal of this document is already waiting for approval.");
        }
        jdbc.update("insert into doc_disposal (doc_key, file_name, reason, status, requested_by, requested_at) values"
                + " (?, ?, ?, 'PENDING', ?, ?)", key, doc.get("fileName"), reason, user.getUsername(), LocalDateTime.now());
        audit.record(user.getUsername(), "Document disposal requested", "Document", key, reason);
        return first("select * from doc_disposal where doc_key = ? and status = 'PENDING'", key);
    }

    /**
     * A second person approves: the file is deleted from storage, its indexed text cleared (no search can find it),
     * and the row kept as DISPOSED with the decision in the evidence chain. Retention and holds are checked again.
     */
    @Transactional
    public Map<String, Object> decideDisposal(Long id, boolean approve) {
        AppUser user = user("MANAGE_CONFIG");
        Map<String, Object> d = first("select * from doc_disposal where id = ?", id);
        if (d == null) {
            throw new NotFoundException("Disposal request " + id + " not found");
        }
        if (!"PENDING".equals(d.get("status"))) {
            throw new ConflictException("Disposal request " + id + " is " + d.get("status") + ".");
        }
        if (user.getUsername().equalsIgnoreCase((String) d.get("requestedBy"))) {
            throw new NoAuthorizationException("You asked for this disposal, so another person decides it.");
        }
        String key = (String) d.get("docKey");
        if (approve) {
            Map<String, Object> doc = disposable(key);
            String table = key.startsWith("P:") ? "ins_policy_document" : "ins_claim_document";
            documentFiles.dispose((String) doc.get("storagePath"));
            jdbc.update("update " + table + " set status = 'DISPOSED', search_text = null where id = ?", doc.get("id"));
        }
        jdbc.update("update doc_disposal set status = ?, decided_by = ?, decided_at = ? where id = ?",
                approve ? "DISPOSED" : "REJECTED", user.getUsername(), LocalDateTime.now(), id);
        audit.record(user.getUsername(), approve ? "Document disposed" : "Document disposal rejected", "Document", key,
                "Requested by " + d.get("requestedBy") + ": " + d.get("reason"));
        return first("select * from doc_disposal where id = ?", id);
    }

    /** The document behind a key, refused unless its retention has passed and it is not on legal hold. */
    private Map<String, Object> disposable(String key) {
        String table;
        String docClass;
        if (key.startsWith("P:")) {
            table = "ins_policy_document";
            docClass = "Policy document";
        } else if (key.startsWith("C:")) {
            table = "ins_claim_document";
            docClass = "Claim evidence";
        } else {
            throw new IllegalArgumentException("Only policy documents and claim evidence are disposed of here.");
        }
        long id;
        try {
            id = Long.parseLong(key.substring(2));
        } catch (NumberFormatException e) {
            throw new NotFoundException("Document " + key + " not found");
        }
        Map<String, Object> doc = first("select id, file_name, storage_path, status, uploaded_at from " + table
                + " where id = ?", id);
        if (doc == null) {
            throw new NotFoundException("Document " + key + " not found");
        }
        if ("DISPOSED".equals(doc.get("status"))) {
            throw new ConflictException(doc.get("fileName") + " was already disposed of.");
        }
        Map<String, Object> control = first("select legal_hold, hold_reason from doc_control where doc_key = ?", key);
        if (control != null && Boolean.TRUE.equals(control.get("legalHold"))) {
            throw new ConflictException(doc.get("fileName") + " is on legal hold (" + control.get("holdReason")
                    + "), so it cannot be disposed of.");
        }
        Integer years = jdbc.queryForObject("select retain_years from doc_retention where document_class = ?",
                Integer.class, docClass);
        LocalDate retainUntil = ((LocalDateTime) doc.get("uploadedAt")).toLocalDate().plusYears(years == null ? 7 : years);
        if (!retainUntil.isBefore(LocalDate.now())) {
            throw new ConflictException(doc.get("fileName") + " must be kept until " + retainUntil + ".");
        }
        return doc;
    }

    /** {documentClass, retainYears} */
    @Transactional
    public Map<String, Object> saveRetention(JsonNode body) {
        AppUser user = user("MANAGE_CONFIG");
        int years = body.path("retainYears").asInt(0);
        if (years < 1 || years > 100) {
            throw new IllegalArgumentException("Retention must be between 1 and 100 years.");
        }
        if (jdbc.update("update doc_retention set retain_years = ? where document_class = ?", years,
                required(body, "documentClass")) == 0) {
            throw new NotFoundException("Unknown document class");
        }
        audit.record(user.getUsername(), "Retention changed", "Document class", required(body, "documentClass"),
                years + " years");
        return Map.of("documentClass", required(body, "documentClass"), "retainYears", years);
    }

    // ── payouts beside the claims register ────────────────────────────────────────────────────────

    /** Hospital cash events, credit claim allocations and contractual benefits, in one list. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> payouts() {
        user("READ_CLAIM");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.query("select record_ref as ref, policy_ref, customer_name as payee, event_type"
                + " as detail, amount, status, created_at as raised from cc_event", ROW)) {
            r.put("kind", "Hospital cash");
            r.put("page", "care-cash");
            out.add(r);
        }
        for (Map<String, Object> r : jdbc.query("select record_ref as ref, policy_ref, borrower_name as payee, claim_ref"
                + " as detail, lender_amount, estate_amount, status, created_at as raised from crd_loan where status ="
                + " 'CLAIMED'", ROW)) {
            r.put("kind", "Credit life");
            r.put("page", "credit-life");
            r.put("amount", ((java.math.BigDecimal) r.get("lenderAmount")).add((java.math.BigDecimal) r.get("estateAmount")));
            r.put("detail", "Lender " + r.get("lenderAmount") + " · estate " + r.get("estateAmount"));
            out.add(r);
        }
        for (Map<String, Object> r : jdbc.query("select record_ref as ref, policy_ref, customer_name as payee,"
                + " benefit_type as detail, amount, status, created_at as raised from pol_benefit_event", ROW)) {
            r.put("kind", "Contractual benefit");
            r.put("page", "servicing");
            out.add(r);
        }
        out.sort(Comparator.comparing((Map<String, Object> r) -> (LocalDateTime) r.get("raised")).reversed());
        return out;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    /** A row as a map with camelCase keys, dates as java.time values. */
    private static Map<String, Object> row(ResultSet rs, int n) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String[] parts = meta.getColumnLabel(i).toLowerCase(Locale.ROOT).split("_");
            StringBuilder key = new StringBuilder(parts[0]);
            for (int p = 1; p < parts.length; p++) {
                key.append(Character.toUpperCase(parts[p].charAt(0))).append(parts[p].substring(1));
            }
            Object v = rs.getObject(i);
            if (v instanceof java.sql.Timestamp t) {
                v = t.toLocalDateTime();
            } else if (v instanceof java.sql.Date d) {
                v = d.toLocalDate();
            } else if (v instanceof java.time.OffsetDateTime o) {
                v = o.toLocalDateTime();
            } else if (v instanceof byte[] b) {
                v = b.length > 0 && b[0] != 0;
            } else if (v instanceof Long l && meta.getColumnType(i) == java.sql.Types.INTEGER) {
                v = l.intValue();
            }
            m.put(key.toString(), v);
        }
        return m;
    }

    private Map<String, Object> first(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.query(sql, ROW, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    private AppUser staff() {
        AppUser user = security.authenticatedUser();
        if (!user.isStaffUser() || (user.hasPermissionTo("USE_PARTNER_PORTAL") && !user.hasPermissionTo("READ_DASHBOARD"))) {
            throw new NoAuthorizationException("This is for the insurer's own staff.");
        }
        return user;
    }

    private static String required(JsonNode body, String field) {
        String v = textOr(body, field, null);
        if (v == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return v;
    }

    private static String textOr(JsonNode body, String field, String fallback) {
        if (body == null || !body.hasNonNull(field) || body.get(field).asText().isBlank()) {
            return fallback;
        }
        return body.get(field).asText().trim();
    }
}
