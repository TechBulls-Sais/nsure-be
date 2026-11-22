package com.nsure.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Structured partner submissions (spec 04 agent, employer and lender workspaces). A partner sends data in a fixed
 * shape, checked at once against its own scope and the module's basic rules: an agent's application for a client, an
 * employer's census file (or a joiner or leaver) and renewal data, a lender's new loan (with the borrower's consent
 * and permitted purpose) and balance file. Each lands in the operations queue as a portal request; a member of staff
 * processes it through the owning module's own service and permission (CREATE_APPLICATION, PROCESS_CENSUS,
 * MANAGE_CREDIT), and the result, the record created or the rows refused with their reasons, is shown to the partner.
 * Partners never write to the policy administration records themselves.
 */
@Service
public class PartnerSubmissions {

    static final Set<String> AGENT_TYPES = Set.of("NEW_APPLICATION");
    static final Set<String> EMPLOYER_TYPES = Set.of("CENSUS", "JOINER_LEAVER", "RENEWAL_DATA");
    static final Set<String> LENDER_TYPES = Set.of("NEW_LOAN", "BALANCE_UPDATE", "CREDIT_CLAIM");
    /** The only purpose a lender may send borrower data for (spec 04: borrower consent and permitted purpose). */
    static final String PERMITTED_PURPOSE = "CREDIT_LIFE_COVER";

    private final JdbcTemplate jdbc;
    private final PlatformSecurityContext security;
    private final OpsService ops;
    private final ObjectMapper mapper;
    private final EvidenceChain audit;
    private final com.nsure.portfolio.PortfolioService portfolio;
    private final com.nsure.group.CensusService census;
    private final com.nsure.credit.CreditService credit;
    @org.springframework.beans.factory.annotation.Autowired
    private jakarta.persistence.EntityManager em;

    public PartnerSubmissions(JdbcTemplate jdbc, PlatformSecurityContext security, OpsService ops, ObjectMapper mapper,
            EvidenceChain audit, com.nsure.portfolio.PortfolioService portfolio, com.nsure.group.CensusService census,
            com.nsure.credit.CreditService credit) {
        this.jdbc = jdbc;
        this.security = security;
        this.ops = ops;
        this.mapper = mapper;
        this.audit = audit;
        this.portfolio = portfolio;
        this.census = census;
        this.credit = credit;
    }

    // ── partner side ─────────────────────────────────────────────────────────────────────────────────

    /** {type, ...fields}: validates a structured submission against the partner's scope and records it. */
    @Transactional
    public Map<String, Object> submit(JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("USE_PARTNER_PORTAL");
        Map<String, Object> account = ops.partnerAccount(user);
        String portal = (String) account.get("portalType");
        String scope = (String) account.get("scopeRef");
        String type = upper(text(body, "type"));
        Set<String> allowed = switch (portal) {
            case "AGENT" -> AGENT_TYPES;
            case "EMPLOYER" -> EMPLOYER_TYPES;
            default -> LENDER_TYPES;
        };
        if (type == null || !allowed.contains(type)) {
            throw new IllegalArgumentException("Choose one of: " + String.join(", ", allowed) + ".");
        }
        ObjectNode payload = body.deepCopy();
        payload.remove("type");
        String detail = switch (type) {
            case "NEW_APPLICATION" -> application(payload, scope);
            case "CENSUS" -> censusFile(payload);
            case "JOINER_LEAVER" -> joinerLeaver(payload, scope);
            case "RENEWAL_DATA" -> renewal(payload);
            case "NEW_LOAN" -> loan(payload, scope);
            case "BALANCE_UPDATE" -> balances(payload, scope);
            default -> creditClaim(payload, scope);
        };
        String json = payload.toString();
        if (json.length() > 8000) {
            throw new IllegalArgumentException("This submission is too large for the portal (about 100 census rows at a"
                    + " time); send it in parts.");
        }
        Integer n = jdbc.queryForObject("select count(*) from portal_request", Integer.class);
        String ref = String.format("PRQ-%05d", (n == null ? 0 : n) + 1);
        jdbc.update("insert into portal_request (record_ref, portal_type, scope_ref, request_type, detail, status,"
                + " created_by, created_at, payload) values (?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)", ref, portal, scope, type,
                cut(detail, 2000), user.getUsername(), LocalDateTime.now(), json);
        audit.record(user.getUsername(), "Partner submission received", "Portal request", ref, type + " for " + scope);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ref", ref);
        out.put("status", "OPEN");
        out.put("detail", detail);
        return out;
    }

    private String application(ObjectNode p, String agent) {
        String product = required(p, "product", "Choose the product.");
        String client = text(p, "customerRef");
        String name = text(p, "clientName");
        if (client == null && name == null) {
            throw new IllegalArgumentException("Give the client's customer reference, or their name and details.");
        }
        if (client != null) {
            Integer exists = jdbc.queryForObject("select count(*) from ins_customer where record_ref = ?",
                    Integer.class, client.toUpperCase(Locale.ROOT));
            if (exists == null || exists == 0) {
                throw new NotFoundException("Customer " + client + " not found");
            }
            p.put("customerRef", client.toUpperCase(Locale.ROOT));
        }
        Integer products = jdbc.queryForObject("select count(*) from ins_product where name = ?", Integer.class, product);
        if (products == null || products == 0) {
            throw new IllegalArgumentException("Unknown product " + product + ".");
        }
        // The client's disclosures and declaration travel with the application (the agent records the client's answers).
        if (!p.path("declarationAccepted").asBoolean(false)) {
            throw new IllegalArgumentException("Confirm the client read and accepted the declaration.");
        }
        if (!p.path("disclosures").isObject() || p.path("disclosures").isEmpty()) {
            throw new IllegalArgumentException("Record the client's answers to the disclosure questions.");
        }
        p.put("intermediaryCode", agent);
        return "Application for " + (client != null ? client : name) + ": " + product
                + (text(p, "tier") == null ? "" : " " + text(p, "tier"));
    }

    private String censusFile(ObjectNode p) {
        String csv = required(p, "csv", "Attach the census file (CSV text).");
        String first = csv.strip().lines().findFirst().orElse("");
        if (!first.replace(" ", "").toLowerCase(Locale.ROOT).startsWith("memberkey,firstname,lastname,dateofbirth")) {
            throw new IllegalArgumentException("The first line must be the census header: memberKey,firstName,lastName,"
                    + "dateOfBirth,category,salary,action,effectiveDate");
        }
        long rows = csv.strip().lines().filter(l -> !l.isBlank()).count() - 1;
        if (rows < 1) {
            throw new IllegalArgumentException("The census file has no member rows.");
        }
        String mode = upper(text(p, "mode"));
        p.put("mode", mode == null ? "PARTIAL" : mode);
        return "Census file: " + rows + " member row(s), " + p.get("mode").asText().toLowerCase(Locale.ROOT) + " mode";
    }

    /** A joiner or leaver becomes a one-row census in the template's columns. */
    private String joinerLeaver(ObjectNode p, String scheme) {
        String kind = upper(required(p, "kind", "Say whether this is a JOINER or a LEAVER."));
        if (!Set.of("JOINER", "LEAVER").contains(kind)) {
            throw new IllegalArgumentException("Kind must be JOINER or LEAVER.");
        }
        String key = required(p, "memberKey", "Give the member's staff number (member key).");
        String effective = required(p, "effectiveDate", "Give the date it takes effect.");
        date(effective);
        String action = "JOINER".equals(kind) ? "ADD" : "LEAVE";
        if ("LEAVER".equals(kind)) {
            Integer member = jdbc.queryForObject("select count(*) from grp_member m join ins_scheme s on s.id ="
                    + " m.scheme_id where s.record_ref = ? and m.member_key = ?", Integer.class, scheme, key);
            if (member == null || member == 0) {
                throw new IllegalArgumentException("Member " + key + " is not on scheme " + scheme + ".");
            }
        }
        String csv = String.join(",", "memberKey", "firstName", "lastName", "dateOfBirth", "category", "salary",
                "action", "effectiveDate") + "\n" + String.join(",", key, value(p, "firstName"), value(p, "lastName"),
                value(p, "dateOfBirth"), value(p, "category"), value(p, "salary"), action, effective);
        p.put("csv", csv);
        p.put("mode", "PARTIAL");
        return ("JOINER".equals(kind) ? "Joiner " : "Leaver ") + key + " from " + effective;
    }

    private String renewal(ObjectNode p) {
        int members = p.path("memberCount").asInt(-1);
        String salaries = required(p, "totalSalaries", "Give the total annual salaries.");
        if (members < 1) {
            throw new IllegalArgumentException("Give the number of members at renewal.");
        }
        new BigDecimal(salaries.replace(",", ""));
        return "Renewal data: " + members + " members, total salaries " + salaries;
    }

    private String loan(ObjectNode p, String lender) {
        required(p, "lenderLoanId", "Give your loan number.");
        String customer = required(p, "customerRef", "Give the borrower's customer reference.");
        required(p, "principal", "Give the principal.");
        required(p, "termMonths", "Give the term in months.");
        // Spec 04 lender boundary: the borrower agreed, and the data is sent for credit life cover only.
        if (!p.path("borrowerConsent").asBoolean(false)) {
            throw new IllegalArgumentException("Confirm the borrower consented to the insurer receiving their loan details.");
        }
        String purpose = upper(text(p, "purpose"));
        if (!PERMITTED_PURPOSE.equals(purpose)) {
            throw new IllegalArgumentException("Loan details may be sent only for credit life cover (purpose "
                    + PERMITTED_PURPOSE + ").");
        }
        Integer exists = jdbc.queryForObject("select count(*) from ins_customer where record_ref = ?", Integer.class,
                customer.toUpperCase(Locale.ROOT));
        if (exists == null || exists == 0) {
            throw new NotFoundException("Customer " + customer + " not found");
        }
        p.put("customerRef", customer.toUpperCase(Locale.ROOT));
        p.put("lenderRef", lender);
        String basis = upper(text(p, "premiumBasis"));
        p.put("premiumBasis", basis == null ? "SINGLE" : basis);
        return "New loan " + text(p, "lenderLoanId") + " for " + customer + ": " + text(p, "principal") + " over "
                + text(p, "termMonths") + " months";
    }

    private String balances(ObjectNode p, String lender) {
        required(p, "batchReference", "Give the balance file's reference.");
        JsonNode rows = p.get("rows");
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            throw new IllegalArgumentException("List at least one balance row.");
        }
        List<String> unknown = new ArrayList<>();
        for (JsonNode row : rows) {
            String id = row.path("lenderLoanId").asText("").trim();
            Integer mine = jdbc.queryForObject("select count(*) from crd_loan where lender_ref = ? and lender_loan_id = ?",
                    Integer.class, lender, id);
            if (mine == null || mine == 0) {
                unknown.add(id.isEmpty() ? "(blank)" : id);
            }
            date(row.path("effectiveDate").asText(""));
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("These loans are not yours or not registered: " + String.join(", ", unknown)
                    + ".");
        }
        return "Balance file " + text(p, "batchReference") + ": " + rows.size() + " row(s)";
    }

    private String creditClaim(ObjectNode p, String lender) {
        String id = required(p, "lenderLoanId", "Give your loan number.");
        date(required(p, "eventDate", "Give the date of the event."));
        required(p, "cause", "Say what happened (death, disability ...).");
        Integer mine = jdbc.queryForObject("select count(*) from crd_loan where lender_ref = ? and lender_loan_id = ?",
                Integer.class, lender, id);
        if (mine == null || mine == 0) {
            throw new IllegalArgumentException("Loan " + id + " is not one of yours.");
        }
        return "Credit claim on loan " + id + " (" + text(p, "cause") + ")";
    }

    /**
     * An agent may quote only for a client in its own book (spec 04: no implied access to every customer): a customer
     * with an application under its code or a policy assigned to it.
     */
    @Transactional(readOnly = true)
    public void requireClientOf(String agent, String customerRef) {
        if (customerRef == null || customerRef.isBlank()) {
            throw new IllegalArgumentException("Give the client's customer reference.");
        }
        Integer n = jdbc.queryForObject("select count(*) from ins_customer c where c.record_ref = ? and (exists (select 1"
                + " from ins_application a where a.customer_ref = c.record_ref and a.intermediary_code = ?) or exists"
                + " (select 1 from ins_policy p join dist_policy_assignment pa on pa.policy_ref = p.record_ref join"
                + " dist_intermediary i on i.id = pa.intermediary_id where p.customer_ref = c.record_ref and"
                + " i.record_ref = ? and pa.ended_on is null))", Integer.class, customerRef.trim().toUpperCase(Locale.ROOT),
                agent, agent);
        if (n == null || n == 0) {
            throw new NotFoundException("Customer " + customerRef + " is not a client in your book.");
        }
    }

    // ── staff side ───────────────────────────────────────────────────────────────────────────────────

    /**
     * {customerRef?}: processes a structured submission through its module, as the member of staff, with that module's
     * permission. The result is recorded on the request for the partner. A module refusal (e.g. a loan term outside
     * the product rule) is returned to the member of staff and changes nothing; they correct or answer the request.
     */
    @Transactional
    public Map<String, Object> process(String ref, JsonNode body) {
        AppUser user = security.authenticatedUser();
        List<Map<String, Object>> rows = jdbc.queryForList("select * from portal_request where record_ref = ?", ref);
        if (rows.isEmpty()) {
            throw new NotFoundException("Request " + ref + " not found");
        }
        Map<String, Object> r = lower(rows.get(0));
        if (!"OPEN".equals(r.get("status"))) {
            throw new ConflictException(ref + " is " + r.get("status") + ".");
        }
        String type = (String) r.get("request_type");
        String scope = (String) r.get("scope_ref");
        ObjectNode payload;
        try {
            payload = r.get("payload") == null ? null : (ObjectNode) mapper.readTree((String) r.get("payload"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            payload = null;
        }
        if (payload == null) {
            throw new IllegalArgumentException(ref + " is a free-text request: action it in its module and answer it.");
        }
        String override = text(body, "customerRef");
        if (override != null) {
            payload.put("customerRef", override.toUpperCase(Locale.ROOT));
        }
        String resultRef;
        String result;
        switch (type) {
            case "NEW_APPLICATION" -> {
                user.validateHasPermissionTo("CREATE_APPLICATION");
                if (text(payload, "customerRef") == null) {
                    throw new IllegalArgumentException("Add " + text(payload, "clientName") + " as a customer first, then"
                            + " process this request with their customer reference.");
                }
                ObjectNode application = mapper.createObjectNode();
                application.put("customerRef", text(payload, "customerRef"));
                application.put("product", text(payload, "product"));
                if (text(payload, "tier") != null) {
                    application.put("tier", text(payload, "tier"));
                }
                application.put("intermediaryCode", scope);
                application.put("declarationAccepted", payload.path("declarationAccepted").asBoolean(false));
                application.set("disclosures", payload.path("disclosures"));
                var created = portfolio.createApplication(application);
                // The application is the agent's (spec 04: the agent follows it in its portal and is paid on it).
                em.createQuery("select a from InsuranceApplication a where a.ref = :ref",
                                com.nsure.portfolio.InsuranceApplication.class)
                        .setParameter("ref", created.id()).getResultStream().findFirst()
                        .ifPresent(a -> a.setIntermediaryCode(scope));
                resultRef = created.id();
                result = "Application " + created.id() + " created (" + created.status() + ")";
            }
            case "CENSUS", "JOINER_LEAVER" -> {
                user.validateHasPermissionTo("PROCESS_CENSUS");
                ObjectNode file = mapper.createObjectNode();
                file.put("externalBatchId", ref);
                file.put("mode", text(payload, "mode") == null ? "PARTIAL" : text(payload, "mode"));
                file.put("csv", text(payload, "csv"));
                var batch = census.upload(scope, file);
                resultRef = batch.ref();
                StringBuilder errors = new StringBuilder();
                batch.rows().stream().filter(row -> row.error() != null && !row.error().isBlank()).limit(20)
                        .forEach(row -> errors.append(errors.length() == 0 ? "" : "; ").append("line ")
                                .append(row.lineNo()).append(" (").append(row.memberKey()).append("): ")
                                .append(row.error()));
                result = "Census batch " + batch.ref() + " staged: " + batch.validRows() + " of " + batch.totalRows()
                        + " row(s) valid" + (batch.invalidRows() > 0 ? "; refused rows: " + errors : "")
                        + ". Staff commit it in Group schemes.";
            }
            case "NEW_LOAN" -> {
                user.validateHasPermissionTo("MANAGE_CREDIT");
                payload.remove("borrowerConsent");
                payload.remove("purpose");
                var loan = credit.submit(payload);
                resultRef = loan.ref();
                result = "Loan " + loan.lenderLoanId() + " registered as " + loan.ref();
            }
            case "BALANCE_UPDATE" -> {
                user.validateHasPermissionTo("MANAGE_CREDIT");
                var batch = credit.balanceBatch(scope, payload);
                resultRef = batch.batchReference();
                result = "Balance file " + batch.batchReference() + ": " + batch.applied() + " applied, "
                        + batch.duplicates() + " duplicate, " + batch.conflicts() + " conflict(s), " + batch.unknown()
                        + " unknown";
            }
            default -> throw new IllegalArgumentException(type.toLowerCase(Locale.ROOT).replace('_', ' ')
                    + " is handled in its module; answer the request when done.");
        }
        jdbc.update("update portal_request set status = 'DONE', result = ?, result_ref = ?, processed_by = ?,"
                + " processed_at = ?, handled_by = ?, handled_note = ? where record_ref = ?", cut(result, 4000),
                resultRef, user.getUsername(), LocalDateTime.now(), user.getUsername(), cut(result, 500), ref);
        audit.record(user.getUsername(), "Partner submission processed", "Portal request", ref, result);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ref", ref);
        out.put("status", "DONE");
        out.put("resultRef", resultRef);
        out.put("result", result);
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> lower(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        row.forEach((k, v) -> m.put(k.toLowerCase(Locale.ROOT), v instanceof java.sql.Clob c ? clob(c) : v));
        return m;
    }

    private static String clob(java.sql.Clob c) {
        try {
            return c.getSubString(1, (int) c.length());
        } catch (java.sql.SQLException e) {
            return null;
        }
    }

    private static void date(String value) {
        try {
            LocalDate.parse(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Dates look like 2026-12-31.");
        }
    }

    private static String value(JsonNode p, String field) {
        String v = text(p, field);
        return v == null ? "" : v.replace(",", " ");
    }

    private static String required(JsonNode p, String field, String message) {
        String v = text(p, field);
        if (v == null) {
            throw new IllegalArgumentException(message);
        }
        return v;
    }

    private static String text(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field)) {
            return null;
        }
        String v = body.get(field).asText().trim();
        return v.isEmpty() ? null : v;
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
