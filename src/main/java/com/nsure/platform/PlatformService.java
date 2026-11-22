package com.nsure.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.Money;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.portfolio.CustomerAccount;
import com.nsure.portfolio.InsuranceApplication;
import com.nsure.portfolio.InsuranceClaim;
import com.nsure.portfolio.InsurancePolicy;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared services that sit beside the transaction modules:
 * <ul>
 *   <li>SH01: possible duplicate parties and a steward-approved merge that keeps both references.</li>
 *   <li>SH09A: the wording and clause library, versioned and approved by a second person.</li>
 *   <li>SH08/SH13: governed data extracts with record counts, control totals and a checksum, and the metric
 *       dictionary every dashboard figure is defined in.</li>
 *   <li>Section 15: governed assistants. They are deterministic rules over the record's own data, registered with an
 *       owner and a switch, logged, and marked for human review. They never write to a policy or claim.</li>
 * </ul>
 */
@Service
public class PlatformService {

    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final PlatformSecurityContext security;
    private final EvidenceChain audit;

    /** Party identity rules, record moves and party events (field-injected; the constructor is shared). */
    @org.springframework.beans.factory.annotation.Autowired
    private com.nsure.portfolio.PartyRegister parties;

    /** Duplicate matching and steward-approved merges (SH01, work package e). */
    @org.springframework.beans.factory.annotation.Autowired
    private com.nsure.portfolio.PartyService partyMaster;

    public PlatformService(EntityManager em, JdbcTemplate jdbc, PlatformSecurityContext security, EvidenceChain audit) {
        this.em = em;
        this.jdbc = jdbc;
        this.security = security;
        this.audit = audit;
    }

    // ── party duplicates and merge (NS-SH01-002) ──────────────────────────────────────────────────

    /**
     * Pairs of customers that may be the same person, with why. A matching name alone is only "possible". The
     * matching (deterministic and configurable fuzzy rules) is the party master's (SH01, PartyService).
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> duplicates() {
        return partyMaster.duplicates();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> merges() {
        user("READ_CUSTOMER");
        return jdbc.query("select surviving_ref, merged_ref, merged_name, reason, records_moved, merged_by, merged_at"
                + " from pty_merge order by id desc", (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("survivingRef", rs.getString("surviving_ref"));
                    m.put("mergedRef", rs.getString("merged_ref"));
                    m.put("mergedName", rs.getString("merged_name"));
                    m.put("reason", rs.getString("reason"));
                    m.put("recordsMoved", rs.getInt("records_moved"));
                    m.put("mergedBy", rs.getString("merged_by"));
                    m.put("mergedAt", rs.getTimestamp("merged_at").toLocalDateTime());
                    return m;
                });
    }

    /**
     * {survivingRef, mergedRef, reason}: asks for a merge. Nothing moves until a data steward who is someone else
     * approves it (NS-SH01-002, PartyService); the request is returned with status PENDING.
     */
    @Transactional
    public Map<String, Object> merge(JsonNode body) {
        return partyMaster.requestMerge(body);
    }

    // ── wording library (NS-SH09A-001) ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> wordings() {
        user("READ_PRODUCT");
        return jdbc.query("select * from doc_wording order by record_ref, version_no desc", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", rs.getString("record_ref"));
            m.put("version", rs.getInt("version_no"));
            m.put("product", rs.getString("product"));
            m.put("clauseType", rs.getString("clause_type"));
            m.put("title", rs.getString("title"));
            m.put("body", rs.getString("body"));
            m.put("status", rs.getString("status"));
            m.put("effectiveFrom", rs.getDate("effective_from") == null ? null : rs.getDate("effective_from").toLocalDate());
            m.put("createdBy", rs.getString("created_by"));
            m.put("approvedBy", rs.getString("approved_by"));
            return m;
        });
    }

    /**
     * {ref?, product, clauseType, title, body}: a new clause, or a new version of an existing one. Issued documents
     * keep the version they were issued with; a new version applies only once approved.
     */
    @Transactional
    public Map<String, Object> saveWording(JsonNode body) {
        AppUser user = user("UPDATE_PRODUCT");
        String ref = text(body, "ref");
        int version = 1;
        if (ref == null) {
            Integer count = jdbc.queryForObject("select count(distinct record_ref) from doc_wording", Integer.class);
            ref = String.format("WRD-%04d", (count == null ? 0 : count) + 1);
        } else {
            Integer latest = jdbc.queryForObject("select max(version_no) from doc_wording where record_ref = ?",
                    Integer.class, ref);
            if (latest == null) {
                throw new NotFoundException("Wording " + ref + " not found");
            }
            version = latest + 1;
        }
        String title = required(body, "title");
        String clause = required(body, "body");
        jdbc.update("insert into doc_wording (record_ref, version_no, product, clause_type, title, body, status,"
                + " created_by, created_at) values (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?)", ref, version,
                required(body, "product"), required(body, "clauseType").toUpperCase(Locale.ROOT), title, clause,
                user.getUsername(), LocalDateTime.now());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ref", ref);
        out.put("version", version);
        out.put("status", "DRAFT");
        return out;
    }

    /** Approves the draft version; the author cannot approve their own wording. */
    @Transactional
    public Map<String, Object> approveWording(String ref, int version) {
        AppUser user = user("ACTIVATE_PRODUCT");
        List<String> author = jdbc.queryForList("select created_by from doc_wording where record_ref = ? and version_no = ?"
                + " and status = 'DRAFT'", String.class, ref, version);
        if (author.isEmpty()) {
            throw new ConflictException(ref + " v" + version + " is not a draft.");
        }
        if (user.getUsername().equalsIgnoreCase(author.get(0))) {
            throw new NoAuthorizationException("You wrote this wording, so someone else must approve it.");
        }
        jdbc.update("update doc_wording set status = 'SUPERSEDED' where record_ref = ? and status = 'APPROVED'", ref);
        jdbc.update("update doc_wording set status = 'APPROVED', effective_from = ?, approved_by = ?, approved_at = ?"
                + " where record_ref = ? and version_no = ?", LocalDate.now(), user.getUsername(), LocalDateTime.now(),
                ref, version);
        audit.record(user.getUsername(), "Wording approved", "Wording", ref, "v" + version);
        return Map.of("ref", ref, "version", version, "status", "APPROVED");
    }

    // ── extracts and the metric dictionary (NS-SH08-007, NS-SH13-003/006) ─────────────────────────

    public List<Map<String, String>> metrics() {
        user("READ_REPORT");
        return List.of(
                metric("Active policies", "Individual contracts with status Active at the cut-off.", "Count of policies",
                        "Policy status; event date", "Operations"),
                metric("Active lives", "Distinct insured lives with cover in force, individual and group counted"
                        + " separately.", "Count of lives", "Cover start/cease dates", "Operations"),
                metric("Premium collected", "Premium receipts allocated and not reversed in the period.", "BWP",
                        "Receipt date (cash basis)", "Finance"),
                metric("Collection rate", "Premium collected ÷ premium due in the period.", "Percent",
                        "Due date for the denominator, receipt date for the numerator", "Finance"),
                metric("Claims ratio", "Claims paid ÷ premium collected for the same period.", "Percent",
                        "Payment date and receipt date", "Actuarial"),
                metric("Claims turnaround", "Days from registration to settlement, for claims settled in the period.",
                        "Days (median)", "Settlement date", "Claims"),
                metric("Persistency (13-month)", "Policies issued 13 months before the cut-off that are still in force ÷"
                        + " policies issued in that month.", "Percent", "Issue date cohort", "Actuarial"),
                metric("Straight-through rate", "Applications accepted by the underwriting rules ÷ applications"
                        + " submitted.", "Percent", "Submission date", "New Business"),
                metric("Ceded premium", "Premium ceded to reinsurers under approved treaties, net of adjustments.", "BWP",
                        "Cession date", "Reinsurance"));
    }

    private static Map<String, String> metric(String name, String definition, String unit, String timeBasis,
            String owner) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("definition", definition);
        m.put("unit", unit);
        m.put("timeBasis", timeBasis);
        m.put("owner", owner);
        return m;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> extractRuns() {
        user("READ_REPORT");
        return jdbc.query("select * from rpt_extract_run order by id desc", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", rs.getString("record_ref"));
            m.put("extractType", rs.getString("extract_type"));
            m.put("asOf", rs.getDate("as_of").toLocalDate());
            m.put("recordCount", rs.getInt("record_count"));
            m.put("controlTotal", rs.getBigDecimal("control_total"));
            m.put("checksum", rs.getString("checksum"));
            m.put("runBy", rs.getString("run_by"));
            m.put("runAt", rs.getTimestamp("run_at").toLocalDateTime());
            return m;
        });
    }

    /**
     * {extractType POLICIES|PREMIUMS|CLAIMS}: produces the extract with its record count, control total and checksum,
     * and records the run. The rows are the valuation and regulatory dataset; valuation itself stays external.
     */
    @Transactional
    public Map<String, Object> extract(JsonNode body) {
        AppUser user = user("READ_REPORT");
        String type = required(body, "extractType").toUpperCase(Locale.ROOT);
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        switch (type) {
            case "POLICIES" -> {
                for (InsurancePolicy p : em.createQuery("select p from InsurancePolicy p order by p.id",
                        InsurancePolicy.class).getResultList()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("policyRef", p.getRef());
                    r.put("customerRef", p.getCustomerRef());
                    r.put("product", p.getProduct());
                    r.put("plan", p.getTier());
                    r.put("status", p.getStatus());
                    r.put("coverStart", p.getCoverStartDate());
                    r.put("sumAssured", p.getAmount());
                    r.put("monthlyPremium", p.getPremium());
                    r.put("premiumFrequency", p.getPremiumFrequency());
                    r.put("terminationReason", p.getTerminationReason());
                    rows.add(r);
                    total = total.add(p.getAmount() == null ? BigDecimal.ZERO : p.getAmount());
                }
            }
            case "PREMIUMS" -> {
                for (Object[] p : em.createQuery("select p.policyRef, p.periodStart, p.periodEnd, p.amount, p.status,"
                        + " p.paymentMethod, p.receiptRef from PremiumPayment p order by p.id", Object[].class)
                        .getResultList()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("policyRef", p[0]);
                    r.put("periodStart", p[1]);
                    r.put("periodEnd", p[2]);
                    r.put("amount", p[3]);
                    r.put("status", p[4]);
                    r.put("method", p[5]);
                    r.put("receiptRef", p[6]);
                    rows.add(r);
                    if (!"REVERSED".equals(p[4])) {
                        total = total.add((BigDecimal) p[3]);
                    }
                }
            }
            case "CLAIMS" -> {
                for (InsuranceClaim c : em.createQuery("select c from InsuranceClaim c order by c.id",
                        InsuranceClaim.class).getResultList()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("claimRef", c.getRef());
                    r.put("policyRef", c.getPolicyRef());
                    r.put("product", c.getProduct());
                    r.put("claimType", c.getClaimType());
                    r.put("eventDate", c.getEventDate());
                    r.put("status", c.getStatus());
                    r.put("claimedAmount", c.getAmount());
                    r.put("paidAmount", c.getPayoutAmount());
                    rows.add(r);
                    total = total.add(c.getPayoutAmount() == null ? BigDecimal.ZERO : c.getPayoutAmount());
                }
            }
            default -> throw new IllegalArgumentException("extractType must be POLICIES, PREMIUMS or CLAIMS.");
        }
        Integer count = jdbc.queryForObject("select count(*) from rpt_extract_run", Integer.class);
        String ref = String.format("EXT-%05d", (count == null ? 0 : count) + 1);
        String checksum = sha256(rows.toString());
        total = Money.of(total);
        jdbc.update("insert into rpt_extract_run (record_ref, extract_type, as_of, record_count, control_total, checksum,"
                + " run_by, run_at) values (?, ?, ?, ?, ?, ?, ?, ?)", ref, type, LocalDate.now(), rows.size(), total,
                checksum, user.getUsername(), LocalDateTime.now());
        audit.record(user.getUsername(), "Data extract produced", "Extract", ref, type + ": " + rows.size()
                + " records, control total " + total);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ref", ref);
        out.put("extractType", type);
        out.put("asOf", LocalDate.now());
        out.put("recordCount", rows.size());
        out.put("controlTotal", total);
        out.put("checksum", checksum);
        out.put("rows", rows);
        return out;
    }

    // ── governed assistants (NS-AIG-001..006) ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> useCases() {
        security.authenticatedUser();
        return jdbc.query("select * from ai_use_case order by code", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", rs.getString("code"));
            m.put("name", rs.getString("name"));
            m.put("purpose", rs.getString("purpose"));
            m.put("owner", rs.getString("owner"));
            m.put("modelVersion", rs.getString("model_version"));
            m.put("enabled", rs.getBoolean("enabled"));
            m.put("updatedBy", rs.getString("updated_by"));
            return m;
        });
    }

    /** {enabled}: the switch for one assistant. Off, the work carries on manually and nothing is blocked. */
    @Transactional
    public List<Map<String, Object>> switchUseCase(String code, JsonNode body) {
        AppUser user = user("MANAGE_CONFIG");
        if (jdbc.update("update ai_use_case set enabled = ?, updated_by = ?, updated_at = ? where code = ?",
                body.path("enabled").asBoolean(false), user.getUsername(), LocalDateTime.now(), code) == 0) {
            throw new NotFoundException("Assistant " + code + " not found");
        }
        audit.record(user.getUsername(), "Assistant " + (body.path("enabled").asBoolean(false) ? "enabled" : "disabled"),
                "Assistant", code, null);
        return useCases();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> interactions() {
        user("READ_AUDIT");
        return jdbc.query("select * from ai_interaction order by id desc", (rs, n) -> interaction(rs.getLong("id"),
                rs.getString("use_case"), rs.getString("subject_ref"), rs.getString("model_version"),
                rs.getString("output"), rs.getString("sources"), rs.getInt("confidence"), rs.getString("review_status"),
                rs.getString("reviewed_by"), rs.getString("requested_by"), rs.getTimestamp("requested_at")
                        .toLocalDateTime()));
    }

    /** What an application still needs, each finding with where it comes from. */
    @Transactional
    public Map<String, Object> applicationCheck(String applicationRef) {
        AppUser user = user("READ_APPLICATION");
        InsuranceApplication app = em.createQuery("select a from InsuranceApplication a where a.ref = :ref",
                InsuranceApplication.class).setParameter("ref", applicationRef).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Application " + applicationRef + " not found"));
        List<String> lines = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        CustomerAccount c = app.getCustomerRef() == null ? null : em.createQuery(
                "select c from CustomerAccount c where c.ref = :ref", CustomerAccount.class)
                .setParameter("ref", app.getCustomerRef()).getResultStream().findFirst().orElse(null);
        if (c == null) {
            lines.add("No customer record is linked.");
        } else {
            if (!"VERIFIED".equals(c.getKycStatus())) {
                lines.add("Identity is not verified (KYC " + c.getKycStatus() + ").");
            }
            if (c.getDateOfBirth() == null) {
                lines.add("Date of birth is missing, so entry age cannot be checked.");
            }
            sources.add("Customer " + c.getRef());
        }
        List<String> uw = jdbc.queryForList("select status from uw_case where application_ref = ?", String.class,
                applicationRef);
        if (uw.isEmpty()) {
            lines.add("Not yet submitted to underwriting.");
        } else {
            sources.add("Underwriting case");
            if (!"ACCEPTED".equals(uw.get(0))) {
                lines.add("Underwriting is " + uw.get(0).toLowerCase(Locale.ROOT).replace('_', ' ') + ".");
            }
            Integer evidence = jdbc.queryForObject("select count(*) from uw_evidence e join uw_case c on"
                    + " c.record_ref = e.case_ref where c.application_ref = ? and e.status = 'REQUESTED'", Integer.class,
                    applicationRef);
            if (evidence != null && evidence > 0) {
                lines.add(evidence + " underwriting evidence request(s) are outstanding.");
            }
        }
        sources.add("Application " + app.getRef() + " (" + app.getStatus() + ")");
        String output = lines.isEmpty() ? "Nothing outstanding: the application is complete on the records held."
                : String.join("\n", lines);
        return log("APPLICATION_CHECK", applicationRef, output, sources, lines.isEmpty() ? 95 : 90, user);
    }

    /** A summary of the claim from its own record, for the assessor. */
    @Transactional
    public Map<String, Object> claimSummary(String claimRef) {
        AppUser user = user("READ_CLAIM");
        InsuranceClaim claim = em.createQuery("select c from InsuranceClaim c where c.ref = :ref", InsuranceClaim.class)
                .setParameter("ref", claimRef).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Claim " + claimRef + " not found"));
        List<String> lines = new ArrayList<>();
        lines.add(claim.getCustomerName() + " claims " + Money.text(claim.getAmount()) + " under " + claim.getProduct()
                + " policy " + claim.getPolicyRef() + ".");
        lines.add("Claim type: " + (claim.getClaimType() == null ? "not stated" : claim.getClaimType()) + "; event date: "
                + (claim.getEventDate() == null ? "not recorded" : claim.getEventDate()) + "; status: "
                + claim.getStatus() + ".");
        InsurancePolicy policy = claim.getPolicyRef() == null ? null : em.createQuery(
                "select p from InsurancePolicy p where p.ref = :ref", InsurancePolicy.class)
                .setParameter("ref", claim.getPolicyRef()).getResultStream().findFirst().orElse(null);
        List<String> sources = new ArrayList<>(List.of("Claim " + claim.getRef()));
        if (policy != null) {
            sources.add("Policy " + policy.getRef());
            lines.add("Policy is " + policy.getStatus() + ", cover from " + policy.getCoverStartDate() + ", sum assured "
                    + Money.text(policy.getAmount()) + ".");
            try {
                LocalDate event = LocalDate.parse(claim.getEventDate().trim());
                if (policy.getCoverStartDate() != null && event.isBefore(policy.getCoverStartDate().plusMonths(6))) {
                    lines.add("Point to check: the event is within 6 months of the cover start (waiting period).");
                }
            } catch (RuntimeException ignored) {
                lines.add("Point to check: the event date is missing or not a date.");
            }
        }
        Integer earlier = jdbc.queryForObject("select count(*) from ins_claim where policy_ref = ? and record_ref <> ?",
                Integer.class, claim.getPolicyRef(), claim.getRef());
        if (earlier != null && earlier > 0) {
            lines.add("Point to check: " + earlier + " other claim(s) exist on this policy.");
        }
        return log("CLAIM_SUMMARY", claimRef, String.join("\n", lines), sources, 85, user);
    }

    /** {customerRef, purpose PREMIUM_REMINDER|EVIDENCE_REQUEST|CLAIM_UPDATE}: a draft for a person to review. */
    @Transactional
    public Map<String, Object> draftMessage(JsonNode body) {
        AppUser user = user("READ_CUSTOMER");
        CustomerAccount c = customer(text(body, "customerRef"));
        String purpose = required(body, "purpose").toUpperCase(Locale.ROOT);
        String first = c.getName().split(" ")[0];
        String text = switch (purpose) {
            case "PREMIUM_REMINDER" -> "Dear " + first + ", this is a reminder that your premium is due. You can pay"
                    + " in the customer portal or at any branch. If you have already paid, please ignore this message.";
            case "EVIDENCE_REQUEST" -> "Dear " + first + ", to continue with your application we still need a document"
                    + " from you. Please sign in to the customer portal to see what is needed and upload it there.";
            case "CLAIM_UPDATE" -> "Dear " + first + ", we are working on your claim. You can follow its progress in"
                    + " the customer portal. We will contact you if we need anything further.";
            default -> throw new IllegalArgumentException("purpose must be PREMIUM_REMINDER, EVIDENCE_REQUEST or"
                    + " CLAIM_UPDATE.");
        };
        return log("DRAFT_MESSAGE", c.getRef(), text, List.of("Approved service wording: " + purpose, "Customer "
                + c.getRef()), 100, user);
    }

    /** Advisory indicators for one customer: never a decision, always shown with the facts behind them. */
    @Transactional
    public Map<String, Object> riskIndicators(String customerRef) {
        AppUser user = user("READ_CUSTOMER");
        CustomerAccount c = customer(customerRef);
        List<InsurancePolicy> policies = em.createQuery("select p from InsurancePolicy p where p.customerRef = :ref",
                InsurancePolicy.class).setParameter("ref", c.getRef()).getResultList();
        long lapsedOrOff = policies.stream().filter(p -> Set.of("Lapsed", "Active - No Cover", "Cancelled")
                .contains(p.getStatus())).count();
        Integer claims = jdbc.queryForObject("select count(*) from ins_claim where customer_ref = ?", Integer.class,
                c.getRef());
        BigDecimal exposure = policies.stream().filter(p -> "Active".equals(p.getStatus())).map(InsurancePolicy::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<String> lines = new ArrayList<>();
        lines.add("Lapse indicator: " + (lapsedOrOff > 0 ? "ELEVATED" : "NORMAL") + " (" + lapsedOrOff + " of "
                + policies.size() + " policies lapsed, cancelled or without cover).");
        lines.add("Claims indicator: " + (claims != null && claims >= 3 ? "REVIEW" : "NORMAL") + " (" + claims
                + " claim(s) on record).");
        lines.add("Exposure: " + Money.text(exposure) + " in force across " + policies.stream().filter(p -> "Active"
                .equals(p.getStatus())).count() + " active policies.");
        lines.add("These are rule-based indicators for a person to weigh. They do not accept, decline or price"
                + " anything.");
        return log("RISK_SCORE", c.getRef(), String.join("\n", lines), List.of("Customer " + c.getRef(), policies.size()
                + " policies", claims + " claims"), 80, user);
    }

    /** {accept, note}: the human review of an assistant's output. */
    @Transactional
    public Map<String, Object> review(Long id, JsonNode body) {
        AppUser user = security.authenticatedUser();
        if (jdbc.update("update ai_interaction set review_status = ?, reviewed_by = ?, review_note = ? where id = ?"
                + " and review_status = 'PENDING'", body.path("accept").asBoolean(false) ? "ACCEPTED" : "REJECTED",
                user.getUsername(), text(body, "note"), id) == 0) {
            throw new ConflictException("That output has already been reviewed.");
        }
        return Map.of("id", id, "reviewStatus", body.path("accept").asBoolean(false) ? "ACCEPTED" : "REJECTED");
    }

    private Map<String, Object> log(String useCase, String subject, String output, List<String> sources, int confidence,
            AppUser user) {
        List<Boolean> enabled = jdbc.queryForList("select enabled from ai_use_case where code = ?", Boolean.class,
                useCase);
        if (enabled.isEmpty()) {
            throw new NotFoundException("Assistant " + useCase + " not found");
        }
        if (!Boolean.TRUE.equals(enabled.get(0))) {
            // NS-AIG-006: switched off, the manual route still works and nothing is lost.
            throw new IllegalArgumentException("This assistant is switched off. Carry on with the manual process.");
        }
        String version = jdbc.queryForObject("select model_version from ai_use_case where code = ?", String.class,
                useCase);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into ai_interaction (use_case, subject_ref, model_version, output, sources, confidence,"
                + " review_status, requested_by, requested_at) values (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)", useCase,
                subject, version, output, String.join("; ", sources), confidence, user.getUsername(), now);
        Long id = jdbc.queryForObject("select max(id) from ai_interaction", Long.class);
        return interaction(id, useCase, subject, version, output, String.join("; ", sources), confidence, "PENDING",
                null, user.getUsername(), now);
    }

    private static Map<String, Object> interaction(Long id, String useCase, String subject, String version,
            String output, String sources, int confidence, String status, String reviewedBy, String requestedBy,
            LocalDateTime at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("useCase", useCase);
        m.put("subjectRef", subject);
        m.put("modelVersion", version);
        m.put("output", List.of(output.split("\n")));
        m.put("sources", sources == null ? List.of() : List.of(sources.split("; ")));
        m.put("confidence", confidence);
        m.put("reviewStatus", status);
        m.put("reviewedBy", reviewedBy);
        m.put("requestedBy", requestedBy);
        m.put("requestedAt", at);
        return m;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private CustomerAccount customer(String ref) {
        if (ref == null) {
            throw new IllegalArgumentException("Choose the customer.");
        }
        return em.createQuery("select c from CustomerAccount c where c.ref = :ref", CustomerAccount.class)
                .setParameter("ref", ref.toUpperCase(Locale.ROOT)).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Customer " + ref + " not found"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(
                    StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    private static String required(JsonNode body, String field) {
        String v = text(body, field);
        if (v == null) {
            throw new IllegalArgumentException(field + " is required.");
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
}
