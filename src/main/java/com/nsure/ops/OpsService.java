package com.nsure.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
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
 *   <li>Workflow (SH10): a definition per kind of work with its team, steps and SLA; one queue across the modules
 *       with ownership claimed atomically; SLA due times counted in working hours from the business calendar.</li>
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

    public OpsService(JdbcTemplate jdbc, PlatformSecurityContext security, EvidenceChain audit) {
        this.jdbc = jdbc;
        this.security = security;
        this.audit = audit;
    }

    // ── partner portals ───────────────────────────────────────────────────────────────────────────

    /** Everything the signed-in partner may see: its own portfolio, scheme or loans, and its requests. */
    @Transactional(readOnly = true)
    public Map<String, Object> portal() {
        AppUser user = user("USE_PARTNER_PORTAL");
        List<Map<String, Object>> account = jdbc.query("select portal_type, scope_ref from portal_account where username = ?",
                ROW, user.getUsername());
        if (account.isEmpty()) {
            throw new NoAuthorizationException("This login is not linked to an agent, employer or lender.");
        }
        String type = (String) account.get(0).get("portalType");
        String scope = (String) account.get(0).get("scopeRef");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("portalType", type);
        out.put("scopeRef", scope);
        switch (type) {
            case "AGENT" -> {
                out.put("partner", first("select record_ref, name, intermediary_type, status, licence_number from"
                        + " dist_intermediary where record_ref = ?", scope));
                out.put("applications", jdbc.query("select record_ref, customer_name, product, amount, premium, status"
                        + " from ins_application where intermediary_code = ? order by id desc", ROW, scope));
                // The agent's book: policies assigned to them, and policies issued from applications they sold.
                out.put("policies", jdbc.query("select p.record_ref, p.customer_name, p.product, p.amount, p.premium,"
                        + " p.status from ins_policy p where p.record_ref in (select a.policy_ref from"
                        + " dist_policy_assignment a join dist_intermediary i on i.id = a.intermediary_id where"
                        + " i.record_ref = ? and a.ended_on is null) or p.application_ref in (select ap.record_ref from"
                        + " ins_application ap where ap.intermediary_code = ?) order by p.id desc", ROW, scope, scope));
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
                + " handled_note from portal_request where created_by = ? order by id desc", ROW, user.getUsername()));
        return out;
    }

    /** {requestType, detail}: a partner's request, acknowledged with a reference (NS-UX-005). */
    @Transactional
    public Map<String, Object> portalRequest(JsonNode body) {
        AppUser user = user("USE_PARTNER_PORTAL");
        Map<String, Object> account = first("select portal_type, scope_ref from portal_account where username = ?",
                user.getUsername());
        if (account == null) {
            throw new NoAuthorizationException("This login is not linked to an agent, employer or lender.");
        }
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
        jdbc.update("delete from portal_account where username = ?", username);
        jdbc.update("insert into portal_account (username, portal_type, scope_ref) values (?, ?, ?)", username, type,
                required(body, "scopeRef"));
        audit.record(user.getUsername(), "Portal access set", "User", username, type + " " + required(body, "scopeRef"));
        return jdbc.query("select * from portal_account order by username", ROW);
    }

    // ── workflow: definitions, calendar and the queue (NS-SH10-001/002/005) ───────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> workflow() {
        staff();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("definitions", jdbc.query("select * from wf_definition order by name", ROW));
        out.put("calendar", first("select * from wf_calendar where id = 1"));
        return out;
    }

    /** {name?, team?, slaHours?, steps?}: changes a definition; the version moves on so older work is traceable. */
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
        jdbc.update("update wf_definition set name = ?, team = ?, sla_hours = ?, steps = ?, version_no = version_no + 1,"
                + " updated_by = ?, updated_at = ? where code = ?", textOr(body, "name", (String) d.get("name")),
                textOr(body, "team", (String) d.get("team")), sla, textOr(body, "steps", (String) d.get("steps")),
                user.getUsername(), LocalDateTime.now(), code);
        audit.record(user.getUsername(), "Workflow definition changed", "Workflow", code, "SLA " + sla + "h");
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

    /** Every open piece of work across the modules, with its team, owner, age and SLA due time. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> queue() {
        staff();
        Map<String, Map<String, Object>> definitions = jdbc.query("select * from wf_definition", ROW).stream()
                .collect(Collectors.toMap(d -> (String) d.get("code"), d -> d));
        Map<String, Object> calendar = first("select * from wf_calendar where id = 1");
        Map<String, String> owners = new LinkedHashMap<>();
        jdbc.query("select item_key, owner from wf_assignment", rs -> {
            owners.put(rs.getString(1), rs.getString(2));
        });
        List<Map<String, Object>> items = new ArrayList<>();
        add(items, "UNDERWRITING", "underwriting", "select record_ref as ref, customer_name as subject, product as detail,"
                + " created_at as opened from uw_case where status in ('REFERRED', 'EVIDENCE_PENDING')");
        add(items, "ENDORSEMENT", "servicing", "select record_ref as ref, policy_ref as subject, endorsement_type as"
                + " detail, requested_at as opened from pol_endorsement where status in ('PENDING_APPROVAL',"
                + " 'REFERRED_UNDERWRITING', 'IMPACT_REVIEW')");
        add(items, "BENEFIT", "servicing", "select record_ref as ref, policy_ref as subject, benefit_type as detail,"
                + " created_at as opened from pol_benefit_event where status = 'DUE'");
        add(items, "CARE_CASH", "care-cash", "select record_ref as ref, life_name as subject, event_type as detail,"
                + " created_at as opened from cc_event where status = 'ASSESSED'");
        add(items, "SCHEME_EVENT", "scheme-lifecycle", "select record_ref as ref, scheme_ref as subject, event_type as"
                + " detail, requested_at as opened from grp_scheme_event where status = 'PENDING'");
        add(items, "GROUP_QUOTE", "scheme-lifecycle", "select record_ref as ref, sponsor_name as subject, benefits as"
                + " detail, created_at as opened from grp_quote where status = 'SUBMITTED'");
        add(items, "RI_RECOVERY", "reinsurance", "select r.record_ref as ref, r.claim_ref as subject, r.status as detail,"
                + " r.created_at as opened from ri_recovery r where r.status in ('EXPECTED', 'DISPUTED', 'WRITE_OFF_PENDING')");
        add(items, "PORTAL_REQUEST", "work-queue", "select record_ref as ref, created_by as subject, request_type as"
                + " detail, created_at as opened from portal_request where status = 'OPEN'");
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> item : items) {
            Map<String, Object> d = definitions.get((String) item.get("workflow"));
            LocalDateTime opened = (LocalDateTime) item.get("opened");
            LocalDateTime due = addWorkingHours(opened, (Integer) d.get("slaHours"), calendar);
            item.put("workflowName", d.get("name"));
            item.put("team", d.get("team"));
            item.put("slaHours", d.get("slaHours"));
            item.put("dueAt", due);
            item.put("breached", now.isAfter(due));
            item.put("owner", owners.get(item.get("key")));
        }
        items.sort(Comparator.comparing(i -> (LocalDateTime) i.get("dueAt")));
        return items;
    }

    private void add(List<Map<String, Object>> items, String workflow, String page, String sql) {
        for (Map<String, Object> r : jdbc.query(sql, ROW)) {
            r.put("workflow", workflow);
            r.put("page", page);
            r.put("key", workflow + ":" + r.get("ref"));
            items.add(r);
        }
    }

    /** Takes ownership of one item. Two people cannot both take it: the second is told who has it. */
    @Transactional
    public Map<String, Object> claim(String key) {
        AppUser user = staff();
        try {
            jdbc.update("insert into wf_assignment (item_key, owner, claimed_at) values (?, ?, ?)", key,
                    user.getUsername(), LocalDateTime.now());
        } catch (org.springframework.dao.DuplicateKeyException taken) {
            String owner = jdbc.queryForObject("select owner from wf_assignment where item_key = ?", String.class, key);
            if (!user.getUsername().equals(owner)) {
                throw new ConflictException("This work is already owned by " + owner + ".");
            }
        }
        return Map.of("key", key, "owner", user.getUsername());
    }

    @Transactional
    public Map<String, Object> release(String key) {
        AppUser user = staff();
        jdbc.update("delete from wf_assignment where item_key = ? and owner = ?", key, user.getUsername());
        return Map.of("key", key);
    }

    /** Adds working hours to a start time, skipping nights, non-working days and holidays. */
    static LocalDateTime addWorkingHours(LocalDateTime start, int hours, Map<String, Object> calendar) {
        int open = (Integer) calendar.get("workStartHour");
        int close = (Integer) calendar.get("workEndHour");
        Set<DayOfWeek> days = Arrays.stream(String.valueOf(calendar.get("workingDays")).split(","))
                .map(d -> switch (d.trim()) {
                    case "MON" -> DayOfWeek.MONDAY;
                    case "TUE" -> DayOfWeek.TUESDAY;
                    case "WED" -> DayOfWeek.WEDNESDAY;
                    case "THU" -> DayOfWeek.THURSDAY;
                    case "FRI" -> DayOfWeek.FRIDAY;
                    case "SAT" -> DayOfWeek.SATURDAY;
                    default -> DayOfWeek.SUNDAY;
                }).collect(Collectors.toSet());
        Set<String> holidays = calendar.get("holidays") == null ? Set.of()
                : Arrays.stream(String.valueOf(calendar.get("holidays")).split(",")).map(String::trim)
                        .collect(Collectors.toSet());
        LocalDateTime t = start;
        long minutes = hours * 60L;
        int guard = 0;
        while (minutes > 0 && guard++ < 5000) {
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
            long available = java.time.Duration.between(t, dayClose).toMinutes();
            long used = Math.min(available, minutes);
            t = t.plusMinutes(used);
            minutes -= used;
        }
        return t;
    }

    // ── documents: register, scan status, retention, legal hold, search (NS-SH09A-004..006) ───────

    /** Every policy and claim document, optionally filtered by a search term. */
    @Transactional(readOnly = true)
    public Map<String, Object> documents(String q) {
        user("READ_POLICY");
        Map<String, Integer> retention = new LinkedHashMap<>();
        jdbc.query("select document_class, retain_years from doc_retention", rs -> {
            retention.put(rs.getString(1), rs.getInt(2));
        });
        Map<String, Map<String, Object>> controls = jdbc.query("select * from doc_control", ROW).stream()
                .collect(Collectors.toMap(c -> (String) c.get("docKey"), c -> c));
        List<Map<String, Object>> docs = new ArrayList<>();
        for (Map<String, Object> d : jdbc.query("select d.id, d.document_type, d.file_name, d.status, d.uploaded_at,"
                + " d.uploaded_by, p.record_ref as owner_ref, p.customer_name from ins_policy_document d join ins_policy p"
                + " on p.id = d.policy_id", ROW)) {
            d.put("docKey", "P:" + d.get("id"));
            d.put("documentClass", "Policy document");
            docs.add(d);
        }
        for (Map<String, Object> d : jdbc.query("select d.id, d.document_type, d.file_name, d.uploaded_at, d.uploaded_by,"
                + " c.record_ref as owner_ref, c.customer_name from ins_claim_document d join ins_claim c on"
                + " c.id = d.claim_id", ROW)) {
            d.put("docKey", "C:" + d.get("id"));
            d.put("documentClass", "Claim evidence");
            docs.add(d);
        }
        String term = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : docs) {
            if (!term.isEmpty() && !(d.get("fileName") + " " + d.get("documentType") + " " + d.get("ownerRef") + " "
                    + d.get("customerName")).toLowerCase(Locale.ROOT).contains(term)) {
                continue;
            }
            Map<String, Object> control = controls.get((String) d.get("docKey"));
            boolean hold = control != null && Boolean.TRUE.equals(control.get("legalHold"));
            LocalDate retainUntil = ((LocalDateTime) d.get("uploadedAt")).toLocalDate()
                    .plusYears(retention.getOrDefault((String) d.get("documentClass"), 7));
            // Every stored file has passed the upload scan; a file that failed it is never stored.
            d.put("scanStatus", "Clean");
            d.put("retainUntil", retainUntil);
            d.put("legalHold", hold);
            d.put("holdReason", control == null ? null : control.get("holdReason"));
            d.put("disposalEligible", !hold && retainUntil.isBefore(today));
            out.add(d);
        }
        out.sort(Comparator.comparing((Map<String, Object> d) -> (LocalDateTime) d.get("uploadedAt")).reversed());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documents", out);
        result.put("retention", retention);
        return result;
    }

    /** {hold, reason}: places or lifts a legal hold. A held document is never eligible for disposal. */
    @Transactional
    public Map<String, Object> legalHold(String docKey, JsonNode body) {
        AppUser user = user("UPDATE_POLICY");
        boolean hold = body.path("hold").asBoolean(false);
        String reason = hold ? required(body, "reason") : null;
        jdbc.update("delete from doc_control where doc_key = ?", docKey);
        jdbc.update("insert into doc_control (doc_key, legal_hold, hold_reason, held_by, held_at) values (?, ?, ?, ?, ?)",
                docKey, hold, reason, user.getUsername(), LocalDateTime.now());
        audit.record(user.getUsername(), hold ? "Legal hold placed" : "Legal hold lifted", "Document", docKey, reason);
        return Map.of("docKey", docKey, "legalHold", hold);
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
