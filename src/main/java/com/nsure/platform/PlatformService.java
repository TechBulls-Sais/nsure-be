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

    /** Sends a reviewed draft as a service message (consent and logging stay with communications). */
    @org.springframework.beans.factory.annotation.Autowired
    private com.nsure.communication.CommunicationService communications;

    /** The governed metric dictionary (NS-SH13-003), owned by reporting. */
    @org.springframework.beans.factory.annotation.Autowired
    private com.nsure.reporting.MetricDictionary dictionary;

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

    /** Every metric definition version, from the governed dictionary (NS-SH13-003). */
    public List<Map<String, Object>> metrics() {
        return dictionary.list();
    }

    public Map<String, Object> proposeMetric(String key, JsonNode body) {
        return dictionary.propose(key, body);
    }

    public Map<String, Object> approveMetric(String key, int version) {
        return dictionary.approve(key, version);
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
        // NS-SH13-005: an extract holds the rows themselves, so it needs the permission to read those records too,
        // and customer references are masked for anyone who may not read customers.
        user.validateHasPermissionTo("CLAIMS".equals(type) ? "READ_CLAIM" : "READ_POLICY");
        boolean mask = !user.hasPermissionTo("READ_CUSTOMER");
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        switch (type) {
            case "POLICIES" -> {
                for (InsurancePolicy p : em.createQuery("select p from InsurancePolicy p order by p.id",
                        InsurancePolicy.class).getResultList()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("policyRef", p.getRef());
                    r.put("customerRef", mask ? "masked" : p.getCustomerRef());
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
                + " records, control total " + total + (mask ? "; customer references masked" : ""));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ref", ref);
        out.put("extractType", type);
        out.put("asOf", LocalDate.now());
        out.put("recordCount", rows.size());
        out.put("controlTotal", total);
        out.put("checksum", checksum);
        out.put("masked", mask);
        out.put("rows", rows);
        return out;
    }

    // ── governed assistants (NS-AIG-001..006) ─────────────────────────────────────────────────────
    // No AI model is called anywhere: each assistant is a set of deterministic rules over the record's own data. The
    // governance around them (register, permitted inputs, approved versions, review authority, overrides, thresholds,
    // switch) is what a model would sit inside once one is approved (decision D15).

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
            m.put("engine", rs.getString("engine"));
            m.put("permittedInputs", rs.getString("permitted_inputs"));
            m.put("outputs", rs.getString("outputs"));
            m.put("reviewPermission", rs.getString("review_permission"));
            m.put("fourEyes", rs.getBoolean("four_eyes"));
            m.put("acceptanceThreshold", rs.getInt("acceptance_threshold"));
            m.put("approvalStatus", rs.getString("approval_status"));
            m.put("enabled", rs.getBoolean("enabled"));
            m.put("updatedBy", rs.getString("updated_by"));
            return m;
        });
    }

    /** {enabled}: the switch for one assistant. Off, the work carries on manually and nothing is blocked. */
    @Transactional
    public List<Map<String, Object>> switchUseCase(String code, JsonNode body) {
        AppUser user = user("MANAGE_CONFIG");
        boolean enable = body.path("enabled").asBoolean(false);
        List<String> approval = jdbc.queryForList("select approval_status from ai_use_case where code = ?",
                String.class, code);
        if (approval.isEmpty()) {
            throw new NotFoundException("Assistant " + code + " not found");
        }
        if (enable && !"APPROVED".equals(approval.get(0))) {
            throw new ConflictException("Assistant " + code + " has no approved version, so it cannot be switched on.");
        }
        jdbc.update("update ai_use_case set enabled = ?, updated_by = ?, updated_at = ? where code = ?", enable,
                user.getUsername(), LocalDateTime.now(), code);
        audit.record(user.getUsername(), "Assistant " + (enable ? "enabled" : "disabled"), "Assistant", code, null);
        return useCases();
    }

    /**
     * The interaction log. NS-AIG-002: an output is shown only to a reader who may read its subject (the use case's
     * review permission); for anyone else the text is withheld and only the metadata shows.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> interactions() {
        AppUser user = user("READ_AUDIT");
        Map<String, String> permissions = new java.util.HashMap<>();
        jdbc.query("select code, review_permission from ai_use_case", rs -> {
            permissions.put(rs.getString(1), rs.getString(2));
        });
        return jdbc.query("select * from ai_interaction order by id desc", (rs, n) -> {
            Map<String, Object> m = interaction(rs);
            String needed = permissions.getOrDefault(rs.getString("use_case"), "READ_CUSTOMER");
            if (!user.hasPermissionTo(needed)) {
                m.put("output", List.of("[Withheld: reading this output needs " + needed + "]"));
                m.put("finalText", null);
                m.put("withheld", true);
            }
            return m;
        });
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
        List<String> rules = new ArrayList<>();
        int checks = 0;
        int known = 0;
        CustomerAccount c = app.getCustomerRef() == null ? null : em.createQuery(
                "select c from CustomerAccount c where c.ref = :ref", CustomerAccount.class)
                .setParameter("ref", app.getCustomerRef()).getResultStream().findFirst().orElse(null);
        checks += 3;
        if (c == null) {
            lines.add("No customer record is linked.");
            rules.add("R1 customer linked: no");
        } else {
            known++;
            rules.add("R1 customer linked: yes");
            if (c.getKycStatus() != null) {
                known++;
            }
            if (!"VERIFIED".equals(c.getKycStatus())) {
                lines.add("Identity is not verified (KYC " + c.getKycStatus() + ").");
                rules.add("R2 KYC verified: no");
            } else {
                rules.add("R2 KYC verified: yes");
            }
            if (c.getDateOfBirth() == null) {
                lines.add("Date of birth is missing, so entry age cannot be checked.");
                rules.add("R3 date of birth present: no");
            } else {
                known++;
                rules.add("R3 date of birth present: yes");
            }
            sources.add("Customer " + c.getRef());
        }
        // Underwriting detail is only read for someone who may see underwriting (NS-AIG-002).
        if (user.hasPermissionTo("READ_UNDERWRITING")) {
            checks += 2;
            List<String> uw = jdbc.queryForList("select status from uw_case where application_ref = ?", String.class,
                    applicationRef);
            if (uw.isEmpty()) {
                lines.add("Not yet submitted to underwriting.");
                rules.add("R4 underwriting case: none");
            } else {
                known += 2;
                sources.add("Underwriting case");
                rules.add("R4 underwriting case: " + uw.get(0));
                if (!"ACCEPTED".equals(uw.get(0))) {
                    lines.add("Underwriting is " + uw.get(0).toLowerCase(Locale.ROOT).replace('_', ' ') + ".");
                }
                Integer evidence = jdbc.queryForObject("select count(*) from uw_evidence e join uw_case c on"
                        + " c.record_ref = e.case_ref where c.application_ref = ? and e.status = 'REQUESTED'",
                        Integer.class, applicationRef);
                rules.add("R5 evidence outstanding: " + evidence);
                if (evidence != null && evidence > 0) {
                    lines.add(evidence + " underwriting evidence request(s) are outstanding.");
                }
            }
        } else {
            rules.add("R4-R5 underwriting not read: the requester may not see underwriting");
        }
        sources.add("Application " + app.getRef() + " (" + app.getStatus() + ")");
        String output = lines.isEmpty() ? "Nothing outstanding: the application is complete on the records held."
                : String.join("\n", lines);
        return log("APPLICATION_CHECK", applicationRef, output, sources, confidence(known, checks), rules, null, user);
    }

    /** A summary of the claim from its own record, for the assessor. Medical evidence is never read. */
    @Transactional
    public Map<String, Object> claimSummary(String claimRef) {
        AppUser user = user("READ_CLAIM");
        InsuranceClaim claim = em.createQuery("select c from InsuranceClaim c where c.ref = :ref", InsuranceClaim.class)
                .setParameter("ref", claimRef).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Claim " + claimRef + " not found"));
        List<String> lines = new ArrayList<>();
        List<String> rules = new ArrayList<>();
        int checks = 4;
        int known = 0;
        lines.add(claim.getCustomerName() + " claims " + Money.text(claim.getAmount()) + " under " + claim.getProduct()
                + " policy " + claim.getPolicyRef() + ".");
        lines.add("Claim type: " + (claim.getClaimType() == null ? "not stated" : claim.getClaimType()) + "; event date: "
                + (claim.getEventDate() == null ? "not recorded" : claim.getEventDate()) + "; status: "
                + claim.getStatus() + ".");
        if (claim.getClaimType() != null) {
            known++;
        }
        InsurancePolicy policy = claim.getPolicyRef() == null ? null : em.createQuery(
                "select p from InsurancePolicy p where p.ref = :ref", InsurancePolicy.class)
                .setParameter("ref", claim.getPolicyRef()).getResultStream().findFirst().orElse(null);
        List<String> sources = new ArrayList<>(List.of("Claim " + claim.getRef()));
        if (policy != null) {
            known++;
            sources.add("Policy " + policy.getRef());
            lines.add("Policy is " + policy.getStatus() + ", cover from " + policy.getCoverStartDate() + ", sum assured "
                    + Money.text(policy.getAmount()) + ".");
            try {
                LocalDate event = LocalDate.parse(claim.getEventDate().trim());
                known++;
                boolean waiting = policy.getCoverStartDate() != null
                        && event.isBefore(policy.getCoverStartDate().plusMonths(6));
                rules.add("W1 event within 6 months of cover start: " + (waiting ? "yes" : "no"));
                if (waiting) {
                    lines.add("Point to check: the event is within 6 months of the cover start (waiting period).");
                }
            } catch (RuntimeException ignored) {
                rules.add("W1 event date usable: no");
                lines.add("Point to check: the event date is missing or not a date.");
            }
        } else {
            rules.add("P1 policy found: no");
        }
        Integer earlier = jdbc.queryForObject("select count(*) from ins_claim where policy_ref = ? and record_ref <> ?",
                Integer.class, claim.getPolicyRef(), claim.getRef());
        known++;
        rules.add("D1 other claims on the policy: " + earlier);
        if (earlier != null && earlier > 0) {
            lines.add("Point to check: " + earlier + " other claim(s) exist on this policy.");
        }
        lines.add("Freshness: read from the claim and policy records at " + LocalDateTime.now().withNano(0) + ".");
        return log("CLAIM_SUMMARY", claimRef, String.join("\n", lines), sources, confidence(known, checks), rules, null,
                user);
    }

    /** Draft purposes and the approved message template each is drafted from (§15 draft communications). */
    private static final Map<String, String> DRAFT_TEMPLATES = Map.of("PREMIUM_REMINDER", "PremiumDueReminder",
            "EVIDENCE_REQUEST", "OperationalNotice", "CLAIM_UPDATE", "ClaimReceived");

    /**
     * {customerRef, purpose PREMIUM_REMINDER|EVIDENCE_REQUEST|CLAIM_UPDATE}: a draft for a person to review. It is
     * rendered from the latest approved email template for the purpose (its code and version are logged); merge fields
     * the records cannot fill are left marked for the reviewer, which lowers the confidence. Record content is treated
     * as data: only plain name text is merged (NS-AIG-003).
     */
    @Transactional
    public Map<String, Object> draftMessage(JsonNode body) {
        AppUser user = user("READ_CUSTOMER");
        CustomerAccount c = customer(text(body, "customerRef"));
        String purpose = required(body, "purpose").toUpperCase(Locale.ROOT);
        String eventType = DRAFT_TEMPLATES.get(purpose);
        if (eventType == null) {
            throw new IllegalArgumentException("purpose must be PREMIUM_REMINDER, EVIDENCE_REQUEST or CLAIM_UPDATE.");
        }
        String name = c.getName() == null ? "" : c.getName().replaceAll("[^\\p{L} '\\-]", "").trim();
        String first = name.split(" ")[0];
        List<Map<String, Object>> template = jdbc.queryForList("select code, version, subject, body from com_template"
                + " where event_type = ? and channel = 'EMAIL' and status = 'APPROVED' order by version desc",
                eventType);
        List<String> rules = new ArrayList<>();
        String text;
        String templateRef;
        int confidence;
        if (template.isEmpty()) {
            text = switch (purpose) {
                case "PREMIUM_REMINDER" -> "Dear " + first + ", this is a reminder that your premium is due. You can"
                        + " pay in the customer portal or at any branch. If you have already paid, please ignore this"
                        + " message.";
                case "EVIDENCE_REQUEST" -> "Dear " + first + ", to continue with your application we still need a"
                        + " document from you. Please sign in to the customer portal to see what is needed.";
                default -> "Dear " + first + ", we are working on your claim. You can follow its progress in the"
                        + " customer portal.";
            };
            templateRef = "built-in fallback wording (no approved template)";
            confidence = 60;
            rules.add("T0 no approved template for " + eventType + ": fallback wording used");
        } else {
            Map<String, Object> t = template.get(0);
            String[] policy = jdbc.query("select record_ref, product, premium, next_premium_due from ins_policy where"
                    + " customer_ref = ? order by id desc", rs -> rs.next() ? new String[] {rs.getString(1),
                            rs.getString(2), rs.getBigDecimal(3) == null ? null : Money.text(rs.getBigDecimal(3)),
                            rs.getDate(4) == null ? null : rs.getDate(4).toString()} : new String[4], c.getRef());
            String policyRef = policy[0];
            String claimRef = jdbc.query("select record_ref from ins_claim where customer_ref = ? order by id desc",
                    rs -> rs.next() ? rs.getString(1) : null, c.getRef());
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("customerName", name);
            fields.put("firstName", first);
            fields.put("policyRef", policyRef);
            fields.put("product", policy[1]);
            fields.put("amount", policy[2]);
            fields.put("dueDate", policy[3]);
            fields.put("claimRef", claimRef);
            fields.put("portalLink", "the Nsure Life customer portal");
            String rendered = value(t, "body");
            int unfilled = 0;
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{\\{(\\w+)}}").matcher(rendered);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String v = fields.get(matcher.group(1));
                if (v == null) {
                    unfilled++;
                    rules.add("T2 merge field " + matcher.group(1) + ": not on record, left for the reviewer");
                }
                matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(v == null
                        ? "[" + matcher.group(1) + "]" : v));
            }
            matcher.appendTail(out);
            text = out.toString();
            templateRef = value(t, "code") + " v" + value(t, "version");
            confidence = Math.max(50, 100 - 15 * unfilled);
            rules.add("T1 template " + templateRef + " (approved) for " + eventType);
        }
        return log("DRAFT_MESSAGE", c.getRef(), text, List.of("Approved template: " + templateRef, "Customer "
                + c.getRef()), confidence, rules, templateRef, user);
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
        List<String> rules = List.of("L1 lapse ELEVATED when any policy is lapsed, cancelled or without cover: "
                + lapsedOrOff, "C1 claims REVIEW at 3 or more claims: " + claims, "E1 exposure = sum assured of Active"
                + " policies");
        // Little history means little to go on: confidence grows with the policies on record (capped).
        int confidence = Math.min(90, 50 + 10 * policies.size());
        return log("RISK_SCORE", c.getRef(), String.join("\n", lines), List.of("Customer " + c.getRef(), policies.size()
                + " policies", claims + " claims"), confidence, rules, null, user);
    }

    /**
     * {decision ACCEPT | OVERRIDE | REJECT, reason, finalText} (or the older {accept, note}): the human review of an
     * assistant's output (NS-AIG-004). The reviewer must hold the use case's review permission, and where the use case
     * requires four eyes (drafted customer messages) must not be the person who asked. A rejection or an override needs
     * a reason; an override keeps the reviewer's own final text. Every review is audit evidence.
     */
    @Transactional
    public Map<String, Object> review(Long id, JsonNode body) {
        AppUser user = security.authenticatedUser();
        Map<String, Object> row = jdbc.query("select i.use_case, i.requested_by, i.review_status, i.output,"
                + " u.review_permission, u.four_eyes from ai_interaction i join ai_use_case u on u.code = i.use_case"
                + " where i.id = ?", rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("useCase", rs.getString(1));
                    m.put("requestedBy", rs.getString(2));
                    m.put("status", rs.getString(3));
                    m.put("output", rs.getString(4));
                    m.put("permission", rs.getString(5));
                    m.put("fourEyes", rs.getBoolean(6));
                    return m;
                }, id);
        if (row == null) {
            throw new NotFoundException("Assistant output " + id + " not found");
        }
        user.validateHasPermissionTo((String) row.get("permission"));
        if (!"PENDING".equals(row.get("status"))) {
            throw new ConflictException("That output has already been reviewed.");
        }
        if (Boolean.TRUE.equals(row.get("fourEyes")) && user.getUsername().equalsIgnoreCase((String) row.get("requestedBy"))) {
            throw new NoAuthorizationException("You asked for this draft, so someone else must review it.");
        }
        String decision = text(body, "decision");
        if (decision == null) {
            decision = body.path("accept").asBoolean(false) ? "ACCEPT" : "REJECT";
        }
        decision = decision.toUpperCase(Locale.ROOT);
        String reason = text(body, "reason") != null ? text(body, "reason") : text(body, "note");
        String finalText = text(body, "finalText");
        String status = switch (decision) {
            case "ACCEPT" -> "ACCEPTED";
            case "OVERRIDE" -> "OVERRIDDEN";
            case "REJECT" -> "REJECTED";
            default -> throw new com.nsure.common.exception.InvalidFieldException("decision",
                    "decision must be ACCEPT, OVERRIDE or REJECT.");
        };
        if (!"ACCEPTED".equals(status) && (reason == null || reason.length() < 5)) {
            throw new com.nsure.common.exception.InvalidFieldException("reason",
                    "Say why the output is " + status.toLowerCase(Locale.ROOT) + " (at least 5 characters).");
        }
        if ("OVERRIDDEN".equals(status) && finalText == null) {
            throw new com.nsure.common.exception.InvalidFieldException("finalText",
                    "An override keeps your own final text: write it.");
        }
        if (finalText != null && finalText.length() > 4000) {
            throw new com.nsure.common.exception.InvalidFieldException("finalText", "The final text is too long.");
        }
        String kept = "OVERRIDDEN".equals(status) ? finalText : "ACCEPTED".equals(status) ? (String) row.get("output")
                : null;
        jdbc.update("update ai_interaction set review_status = ?, reviewed_by = ?, review_note = ?, review_reason = ?,"
                + " final_text = ?, reviewed_at = ? where id = ? and review_status = 'PENDING'", status,
                user.getUsername(), reason, reason, kept, LocalDateTime.now(), id);
        audit.record(user.getUsername(), "Assistant output " + status.toLowerCase(Locale.ROOT), "Assistant",
                row.get("useCase") + "#" + id, reason);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("reviewStatus", status);
        out.put("finalText", kept);
        return out;
    }

    /**
     * §15 draft communications: a reviewed (accepted or overridden) draft is sent as the reviewer's final text through
     * the communications service, which applies consent and logs the message. Unreviewed or rejected drafts cannot be
     * sent; each draft is sent at most once.
     */
    @Transactional
    public Map<String, Object> sendDraft(Long id) {
        AppUser user = security.authenticatedUser();
        Map<String, Object> row = jdbc.query("select use_case, subject_ref, review_status, final_text, sent_ref"
                + " from ai_interaction where id = ?", rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("useCase", rs.getString(1));
                    m.put("customer", rs.getString(2));
                    m.put("status", rs.getString(3));
                    m.put("text", rs.getString(4));
                    m.put("sent", rs.getString(5));
                    return m;
                }, id);
        if (row == null || !"DRAFT_MESSAGE".equals(row.get("useCase"))) {
            throw new NotFoundException("Draft " + id + " not found");
        }
        if (row.get("sent") != null) {
            throw new ConflictException("This draft was already sent as " + row.get("sent") + ".");
        }
        if (!"ACCEPTED".equals(row.get("status")) && !"OVERRIDDEN".equals(row.get("status"))) {
            throw new ConflictException("Only a reviewed and accepted draft can be sent.");
        }
        com.fasterxml.jackson.databind.node.ObjectNode message = new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode();
        message.put("customerRef", (String) row.get("customer"));
        message.put("channel", "EMAIL");
        message.put("subject", "A message from Nsure Life");
        message.put("body", (String) row.get("text"));
        var sent = communications.sendManual(message);
        jdbc.update("update ai_interaction set sent_ref = ? where id = ?", sent.ref(), id);
        audit.record(user.getUsername(), "Reviewed draft sent", "Assistant", "DRAFT_MESSAGE#" + id, sent.ref() + " · "
                + sent.status());
        return Map.of("id", id, "messageRef", sent.ref(), "messageStatus", sent.status());
    }

    /** Share of the checks the records could answer, as a whole percentage: computed, never a constant. */
    private static int confidence(int known, int checks) {
        return checks == 0 ? 0 : Math.round(known * 100f / checks);
    }

    private Map<String, Object> log(String useCase, String subject, String output, List<String> sources, int confidence,
            List<String> rules, String templateRef, AppUser user) {
        List<Boolean> enabled = jdbc.queryForList("select enabled from ai_use_case where code = ?", Boolean.class,
                useCase);
        if (enabled.isEmpty()) {
            throw new NotFoundException("Assistant " + useCase + " not found");
        }
        if (!Boolean.TRUE.equals(enabled.get(0))) {
            // NS-AIG-006: switched off, the manual route still works and nothing is lost.
            throw new IllegalArgumentException("This assistant is switched off. Carry on with the manual process.");
        }
        // NS-AIG-003: a structured output is validated before anyone sees it: sources present, bounded size.
        if (sources.isEmpty() || output.isBlank() || output.length() > 4000) {
            throw new IllegalStateException("Assistant " + useCase + " produced an output that failed validation.");
        }
        String version = jdbc.queryForObject("select model_version from ai_use_case where code = ?", String.class,
                useCase);
        String explanation = String.join("; ", rules);
        explanation = explanation.length() <= 1000 ? explanation : explanation.substring(0, 1000);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("insert into ai_interaction (use_case, subject_ref, model_version, output, sources, confidence,"
                + " review_status, requested_by, requested_at, explanation, template_ref) values (?, ?, ?, ?, ?, ?,"
                + " 'PENDING', ?, ?, ?, ?)", useCase, subject, version, output, String.join("; ", sources), confidence,
                user.getUsername(), now, explanation, templateRef);
        Long id = jdbc.queryForObject("select max(id) from ai_interaction", Long.class);
        return jdbc.queryForObject("select * from ai_interaction where id = ?", (rs, n) -> interaction(rs), id);
    }

    private static Map<String, Object> interaction(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("useCase", rs.getString("use_case"));
        m.put("subjectRef", rs.getString("subject_ref"));
        m.put("modelVersion", rs.getString("model_version"));
        m.put("engine", "Deterministic rules (no AI model)");
        m.put("output", List.of(rs.getString("output").split("\n")));
        String sources = rs.getString("sources");
        m.put("sources", sources == null ? List.of() : List.of(sources.split("; ")));
        m.put("confidence", rs.getInt("confidence"));
        String explanation = rs.getString("explanation");
        m.put("explanation", explanation == null ? List.of() : List.of(explanation.split("; ")));
        m.put("templateRef", rs.getString("template_ref"));
        m.put("reviewStatus", rs.getString("review_status"));
        m.put("reviewedBy", rs.getString("reviewed_by"));
        m.put("reviewReason", rs.getString("review_reason"));
        m.put("reviewedAt", rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at")
                .toLocalDateTime());
        m.put("finalText", rs.getString("final_text"));
        m.put("sentRef", rs.getString("sent_ref"));
        m.put("requestedBy", rs.getString("requested_by"));
        m.put("requestedAt", rs.getTimestamp("requested_at").toLocalDateTime());
        return m;
    }

    private static String value(Map<String, Object> row, String column) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(column)) {
                return e.getValue() == null ? null : e.getValue().toString();
            }
        }
        return null;
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
