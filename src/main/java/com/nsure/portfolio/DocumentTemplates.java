package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Document generation templates (NS-SH09A-002, SH09A template designer). Each kind of document (schedule,
 * certificate, invoice, letter, claim letter) has its own templates, versioned per code. A template is validated
 * before it is saved and again before publication: only the merge fields its kind provides, its required fields
 * present, {@code {{#if field}} ... {{/if}}} clauses balanced, and the rendered sample within its page limit. A draft
 * is published only when someone other than its author approves the rendered sample, whose hash is kept with the
 * approval. A document generated from a template records the template code and version and its checksum, so a later
 * template version never changes an issued document.
 */
@Service
public class DocumentTemplates {

    static final Map<String, List<String>> FIELDS = new LinkedHashMap<>();
    private static final Pattern FIELD = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9]*)\\s*}}");
    private static final Pattern BLOCK = Pattern.compile("\\{\\{#if\\s+([A-Za-z][A-Za-z0-9]*)\\s*}}(.*?)\\{\\{/if}}",
            Pattern.DOTALL);
    /** A conservative page estimate for validation: characters of body text per A4 page in the report layout. */
    static final int CHARS_PER_PAGE = 2800;

    static {
        List<String> policy = List.of("policyRef", "customerName", "customerRef", "product", "status", "sumAssured",
                "premium", "today", "insurerName");
        FIELDS.put("SCHEDULE", policy);
        FIELDS.put("CERTIFICATE", policy);
        FIELDS.put("INVOICE", policy);
        FIELDS.put("LETTER", policy);
        FIELDS.put("CLAIM_LETTER", List.of("claimRef", "policyRef", "customerName", "claimStatus", "amount", "today",
                "insurerName"));
    }

    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final PlatformSecurityContext security;
    private final EvidenceChain audit;
    private final DocumentFiles files;
    private final WorkflowHistory history;
    private final String insurerName;

    public DocumentTemplates(EntityManager em, JdbcTemplate jdbc, PlatformSecurityContext security, EvidenceChain audit,
            DocumentFiles files, WorkflowHistory history,
            @Value("${nsure.insurer.display-name:Nsure Life}") String insurerName) {
        this.em = em;
        this.jdbc = jdbc;
        this.security = security;
        this.audit = audit;
        this.files = files;
        this.history = history;
        this.insurerName = insurerName;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list() {
        user("READ_PRODUCT");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templates", jdbc.queryForList("select id, code, kind, version_no, title, body, required_fields, max_pages,"
                + " status, created_by, created_at, approved_by, approved_at, sample_sha256 from doc_template order by"
                + " code, version_no desc").stream().map(DocumentTemplates::camel).toList());
        out.put("fields", FIELDS);
        return out;
    }

    /** {code, kind, title, body, requiredFields, maxPages}: a new template, or the next version of a code. */
    @Transactional
    public Map<String, Object> save(JsonNode body) {
        AppUser user = user("UPDATE_PRODUCT");
        String code = upper(text(body, "code"));
        if (code == null || !code.matches("[A-Z0-9_]{3,40}")) {
            throw new IllegalArgumentException("Give the template a code of 3 to 40 letters, digits or underscores.");
        }
        List<Map<String, Object>> versions = jdbc.queryForList("select kind, version_no from doc_template where code = ?"
                + " order by version_no desc", code);
        String kind = upper(text(body, "kind"));
        if (!versions.isEmpty()) {
            String existing = String.valueOf(camel(versions.get(0)).get("kind"));
            if (kind != null && !kind.equals(existing)) {
                throw new IllegalArgumentException("Every version of " + code + " is a " + existing + " template.");
            }
            kind = existing;
        }
        if (kind == null || !FIELDS.containsKey(kind)) {
            throw new IllegalArgumentException("Kind must be one of " + String.join(", ", FIELDS.keySet()) + ".");
        }
        String title = text(body, "title");
        String content = text(body, "body");
        if (title == null || title.length() > 150) {
            throw new IllegalArgumentException("Give a title of up to 150 characters.");
        }
        if (content == null || content.length() > 4000) {
            throw new IllegalArgumentException("Write the template body (up to 4000 characters).");
        }
        String required = text(body, "requiredFields");
        int pages = body.path("maxPages").asInt(2);
        List<String> problems = validate(kind, content, required, pages);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        int version = versions.isEmpty() ? 1 : ((Number) camel(versions.get(0)).get("versionNo")).intValue() + 1;
        jdbc.update("insert into doc_template (code, kind, version_no, title, body, required_fields, max_pages, status,"
                + " created_by, created_at) values (?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?)", code, kind, version, title,
                content, required, pages, user.getUsername(), LocalDateTime.now());
        audit.record(user.getUsername(), "Document template drafted", "Document template", code + " v" + version, kind);
        return template(code, version);
    }

    /** The rendered sample (marked as a sample, never filed) of a template under design or of a stored version. */
    @Transactional(readOnly = true)
    public ClaimDocumentService.StoredFile preview(JsonNode body) {
        AppUser user = security.authenticatedUser();
        if (!user.hasPermissionTo("UPDATE_PRODUCT") && !user.hasPermissionTo("ACTIVATE_PRODUCT")) {
            throw new NoAuthorizationException("User has no authority to design document templates");
        }
        String kind = upper(text(body, "kind"));
        if (kind == null || !FIELDS.containsKey(kind)) {
            throw new IllegalArgumentException("Kind must be one of " + String.join(", ", FIELDS.keySet()) + ".");
        }
        byte[] pdf = render("SAMPLE · " + String.valueOf(text(body, "title")), text(body, "body"), this::sample, true);
        return stored("sample.pdf", pdf);
    }

    /**
     * A second person publishes a draft: it is validated again, its sample is rendered and the sample's hash kept as
     * the approved sample output; the previously published version of the code is retired.
     */
    @Transactional
    public Map<String, Object> approve(String code, int version) {
        AppUser user = user("ACTIVATE_PRODUCT");
        Map<String, Object> t = template(code, version);
        if (!"DRAFT".equals(t.get("status"))) {
            throw new ConflictException(code + " v" + version + " is " + t.get("status") + ", not a draft.");
        }
        if (user.getUsername().equalsIgnoreCase((String) t.get("createdBy"))) {
            throw new NoAuthorizationException("You drafted this template version, so another person publishes it.");
        }
        List<String> problems = validate((String) t.get("kind"), (String) t.get("body"), (String) t.get("requiredFields"),
                ((Number) t.get("maxPages")).intValue());
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        byte[] sample = render("SAMPLE · " + t.get("title"), (String) t.get("body"), this::sample, true);
        jdbc.update("update doc_template set status = 'RETIRED' where code = ? and status = 'APPROVED'", code);
        jdbc.update("update doc_template set status = 'APPROVED', approved_by = ?, approved_at = ?, sample_sha256 = ?"
                + " where code = ? and version_no = ?", user.getUsername(), LocalDateTime.now(),
                DocumentCustody.sha256(sample), code, version);
        audit.record(user.getUsername(), "Document template published", "Document template", code + " v" + version,
                "Drafted by " + t.get("createdBy") + "; sample output " + DocumentCustody.sha256(sample));
        return template(code, version);
    }

    /**
     * {templateCode}: generates a document for a policy from the published template, from the policy's data as it
     * stands now, frozen into the document; filed on the policy with the template version and checksum. Required
     * fields must have values.
     */
    @Transactional
    public List<PortfolioViews.DocumentView> generateForPolicy(String policyRef, JsonNode body) {
        AppUser user = security.authenticatedUser();
        if (!user.hasPermissionTo("UPDATE_POLICY") && !user.hasPermissionTo("CREATE_POLICY")) {
            throw new NoAuthorizationException("User has no authority to issue policy documents");
        }
        String code = upper(text(body, "templateCode"));
        List<Map<String, Object>> rows = jdbc.queryForList("select * from doc_template where code = ? and status ="
                + " 'APPROVED'", code);
        if (rows.isEmpty()) {
            throw new NotFoundException("No published template " + code);
        }
        Map<String, Object> t = camel(rows.get(0));
        if ("CLAIM_LETTER".equals(t.get("kind"))) {
            throw new IllegalArgumentException(code + " is a claim letter, not a policy document.");
        }
        InsurancePolicy policy = em.createQuery("select p from InsurancePolicy p where p.ref = :ref", InsurancePolicy.class)
                .setParameter("ref", policyRef).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Policy " + policyRef + " not found"));
        Map<String, String> values = new LinkedHashMap<>();
        values.put("policyRef", policy.getRef());
        values.put("customerName", policy.getCustomerName());
        values.put("customerRef", policy.getCustomerRef() == null ? "" : policy.getCustomerRef());
        values.put("product", policy.getProduct());
        values.put("status", policy.getStatus());
        values.put("sumAssured", policy.getAmount() == null ? "" : PdfReport.money(policy.getAmount()));
        values.put("premium", policy.getPremium() == null ? "" : PdfReport.money(policy.getPremium()));
        values.put("today", LocalDate.now().toString());
        values.put("insurerName", insurerName);
        String required = (String) t.get("requiredFields");
        if (required != null) {
            for (String field : required.split(",")) {
                if (!field.isBlank() && (values.get(field.trim()) == null || values.get(field.trim()).isBlank())) {
                    throw new IllegalArgumentException("Policy " + policy.getRef() + " has no " + field.trim()
                            + ", which " + code + " requires.");
                }
            }
        }
        byte[] pdf = render(t.get("title") + " " + policy.getRef(), (String) t.get("body"),
                f -> values.getOrDefault(f, ""), false);
        String path = files.write(policy.getRef(), "pdf", pdf);
        PolicyDocument document = new PolicyDocument(policy.getId(), String.valueOf(t.get("title")),
                code.toLowerCase(Locale.ROOT) + "-" + policy.getRef() + ".pdf", "application/pdf", pdf.length, path,
                user.getUsername(), 0);
        document.seal(DocumentCustody.sha256(pdf));
        document.generatedFrom("Template " + code + " v" + t.get("versionNo"), null, null, null);
        em.persist(document);
        em.flush();
        history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Document Issued",
                policy.getStatus(), policy.getStatus(), t.get("title") + " from template " + code + " v"
                        + t.get("versionNo"), user.getUsername());
        audit.record(user.getUsername(), "Document generated from template", "Policy", policy.getRef(), code + " v"
                + t.get("versionNo") + " · sha256 " + DocumentCustody.sha256(pdf));
        return PolicyDocumentService.views(em, policy.getId());
    }

    /** Validation before saving and before publication. */
    static List<String> validate(String kind, String body, String required, int maxPages) {
        List<String> problems = new ArrayList<>();
        List<String> known = FIELDS.getOrDefault(kind, List.of());
        Set<String> used = new LinkedHashSet<>();
        Matcher m = FIELD.matcher(body);
        while (m.find()) {
            used.add(m.group(1));
        }
        Matcher b = Pattern.compile("\\{\\{#if\\s+([A-Za-z][A-Za-z0-9]*)\\s*}}").matcher(body);
        int opens = 0;
        while (b.find()) {
            opens++;
            used.add(b.group(1));
        }
        int closes = body.split("\\{\\{/if}}", -1).length - 1;
        if (opens != closes) {
            problems.add("Every {{#if field}} clause needs one {{/if}} (" + opens + " opened, " + closes + " closed).");
        }
        List<String> unknown = used.stream().filter(f -> !known.contains(f)).toList();
        if (!unknown.isEmpty()) {
            problems.add("Unknown merge field(s) for a " + kind.toLowerCase(Locale.ROOT).replace('_', ' ') + ": "
                    + String.join(", ", unknown) + ". Use only: " + String.join(", ", known) + ".");
        }
        if (required != null) {
            for (String field : required.split(",")) {
                String f = field.trim();
                if (!f.isEmpty() && !known.contains(f)) {
                    problems.add("Required field " + f + " is not a field of this kind.");
                } else if (!f.isEmpty() && !used.contains(f)) {
                    problems.add("Required field " + f + " does not appear in the body.");
                }
            }
        }
        if (maxPages < 1 || maxPages > 20) {
            problems.add("The page limit must be between 1 and 20.");
        } else if (body.length() > maxPages * CHARS_PER_PAGE) {
            problems.add("The text is longer than " + maxPages + " page(s) allow.");
        }
        return problems;
    }

    private byte[] render(String title, String body, Function<String, String> values, boolean sample) {
        String text = body == null ? "" : body.replace("\\n", "\n");
        Matcher block = BLOCK.matcher(text);
        StringBuilder out = new StringBuilder();
        while (block.find()) {
            String value = values.apply(block.group(1));
            block.appendReplacement(out, Matcher.quoteReplacement(value == null || value.isBlank() ? "" : block.group(2)));
        }
        block.appendTail(out);
        Matcher field = FIELD.matcher(out.toString());
        StringBuilder done = new StringBuilder();
        while (field.find()) {
            String value = values.apply(field.group(1));
            field.appendReplacement(done, Matcher.quoteReplacement(value == null ? "" : value));
        }
        field.appendTail(done);
        PdfReport report = new PdfReport(title, title, sample ? "Sample output with example values. Not an issued"
                + " document." : insurerName + " · " + LocalDate.now());
        for (String paragraph : done.toString().split("\n")) {
            report.paragraph(paragraph.isBlank() ? " " : paragraph);
        }
        return report.bytes();
    }

    private String sample(String field) {
        return switch (field) {
            case "policyRef" -> "POL-00123";
            case "claimRef" -> "CLM-0045";
            case "customerName" -> "Kagiso Molefe";
            case "customerRef" -> "CUS-1001";
            case "product" -> "Term Life";
            case "status", "claimStatus" -> "Active";
            case "sumAssured", "amount" -> "BWP 100,000.00";
            case "premium" -> "BWP 150.00";
            case "today" -> LocalDate.now().toString();
            case "insurerName" -> insurerName;
            default -> "[" + field + "]";
        };
    }

    private Map<String, Object> template(String code, int version) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from doc_template where code = ? and version_no = ?",
                code, version);
        if (rows.isEmpty()) {
            throw new NotFoundException("Template " + code + " v" + version + " not found");
        }
        return camel(rows.get(0));
    }

    private static ClaimDocumentService.StoredFile stored(String name, byte[] pdf) {
        return new ClaimDocumentService.StoredFile(name, "application/pdf", pdf.length,
                new org.springframework.core.io.ByteArrayResource(pdf));
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    private static Map<String, Object> camel(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> {
            StringBuilder name = new StringBuilder();
            boolean up = false;
            for (char ch : k.toLowerCase(Locale.ROOT).toCharArray()) {
                if (ch == '_') {
                    up = true;
                } else {
                    name.append(up ? Character.toUpperCase(ch) : ch);
                    up = false;
                }
            }
            out.put(name.toString(), v instanceof java.sql.Timestamp ts ? ts.toLocalDateTime().toString() : v);
        });
        return out;
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
}
