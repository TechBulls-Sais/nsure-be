package com.nsure.group;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.Money;
import com.nsure.common.exception.ConflictException;
import com.nsure.group.PlanRecords.BenefitTerms;
import com.nsure.group.PlanRecords.ProductRule;
import com.nsure.integration.Events;
import com.nsure.portfolio.GroupScheme;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Benefit rules and their precedence (NS-GL02-002..006, AT-GL02-01, section 08 packs).
 *
 * <p>Each benefit's parameters (CI condition set and survival period, family limits, income-protection variant and
 * deferred period, disability definition and partial benefit, credit caps, cessation ages) have a product default
 * and bounds in {@code grp_product_rule}. A scheme may override them at scheme level and per category; the category
 * value wins over the scheme value, which wins over the product default. An override of a parameter the product
 * does not allow to change, or beyond its bounds, fails validation before anything waits for approval. Every change
 * is a new version that a second person approves; it then applies from its effective date.
 */
@Service
public class BenefitRules {

    public static final Set<String> BENEFIT_TYPES = Set.of("GLA", "LUMP_SUM_DISABILITY", "CRITICAL_ILLNESS", "FAMILY",
            "INCOME_PROTECTION", "CREDIT");

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final GroupRecords records;
    private final EvidenceChain audit;
    private final ObjectMapper mapper;

    public BenefitRules(EntityManager em, PlatformSecurityContext security, GroupRecords records, EvidenceChain audit,
            ObjectMapper mapper) {
        this.em = em;
        this.security = security;
        this.records = records;
        this.audit = audit;
        this.mapper = mapper;
    }

    // ── product rules ──────────────────────────────────────────────────────────────────────────────

    List<ProductRule> rules(String benefitType) {
        return em.createQuery("select r from GroupProductRule r where r.benefitType = :b order by r.displayOrder",
                ProductRule.class).setParameter("b", benefitType).getResultList();
    }

    private ProductRule rule(String benefitType, String parameter) {
        return rules(benefitType).stream().filter(r -> r.parameter.equals(parameter)).findFirst().orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> productRules() {
        security.authenticatedUser().validateHasPermissionTo("READ_SCHEME");
        return em.createQuery("select r from GroupProductRule r order by r.benefitType, r.displayOrder", ProductRule.class)
                .getResultList().stream().map(BenefitRules::ruleView).toList();
    }

    private static Map<String, Object> ruleView(ProductRule r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("benefitType", r.benefitType);
        v.put("parameter", r.parameter);
        v.put("label", r.label);
        v.put("valueType", r.valueType);
        v.put("defaultValue", r.defaultValue);
        v.put("minValue", r.minValue);
        v.put("maxValue", r.maxValue);
        v.put("allowedValues", r.allowedValues == null ? null : List.of(r.allowedValues.split(",")));
        v.put("overridable", r.canOverride());
        v.put("source", r.source);
        return v;
    }

    /** The benefit's main amount the product caps (family member amount, credit death/disability); null when none. */
    public BigDecimal productMaximum(String benefitType) {
        String parameter = switch (benefitType) {
            case "FAMILY" -> "MEMBER_AMOUNT";
            case "CREDIT" -> "DEATH_DISABILITY_MAX";
            default -> null;
        };
        ProductRule r = parameter == null ? null : rule(benefitType, parameter);
        return r == null || r.maxValue == null ? null : new BigDecimal(r.maxValue);
    }

    /**
     * Checks a scheme benefit's amount fields against the product (AT-GL02-01): a fixed amount above the product's
     * maximum, a salary multiple or income percentage above its maximum, or a deferred period outside its bounds.
     */
    public void checkSchemeBenefit(String type, String basis, BigDecimal multiple, BigDecimal fixed, Integer waitingMonths) {
        BigDecimal max = productMaximum(type);
        if (max != null && fixed != null && fixed.compareTo(max) > 0) {
            throw new IllegalArgumentException(label(type) + " cannot exceed the product maximum of " + Money.display(max)
                    + "; " + Money.display(fixed) + " was asked for.");
        }
        if (multiple != null && "SALARY_MULTIPLE".equals(basis)) {
            bound(type, "MAX_SALARY_MULTIPLE", multiple, "salary multiple");
        }
        if (multiple != null && "MONTHLY_INCOME_PERCENT".equals(basis)) {
            bound(type, "INCOME_PERCENT", multiple, "income percentage");
        }
        if (waitingMonths != null && "INCOME_PROTECTION".equals(type)) {
            bound(type, "DEFERRED_MONTHS", BigDecimal.valueOf(waitingMonths), "deferred period");
        }
    }

    private void bound(String type, String parameter, BigDecimal value, String what) {
        ProductRule r = rule(type, parameter);
        if (r == null) {
            return;
        }
        if (r.maxValue != null && value.compareTo(new BigDecimal(r.maxValue)) > 0) {
            throw new IllegalArgumentException(label(type) + ": a " + what + " of " + value.stripTrailingZeros()
                    .toPlainString() + " is above the product maximum of " + r.maxValue + ".");
        }
        if (r.minValue != null && value.compareTo(new BigDecimal(r.minValue)) < 0) {
            throw new IllegalArgumentException(label(type) + ": a " + what + " of " + value.stripTrailingZeros()
                    .toPlainString() + " is below the product minimum of " + r.minValue + ".");
        }
    }

    /** Every reason the values break the product's rules: unknown, prohibited, out of bounds or not an allowed value. */
    List<String> violations(String type, JsonNode parameters) {
        List<String> out = new ArrayList<>();
        if (parameters == null || parameters.isNull()) {
            return out;
        }
        Map<String, ProductRule> rules = new LinkedHashMap<>();
        rules(type).forEach(r -> rules.put(r.parameter, r));
        for (Iterator<Map.Entry<String, JsonNode>> it = parameters.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            ProductRule r = rules.get(e.getKey());
            String value = e.getValue().isNull() ? null : e.getValue().asText().trim();
            if (r == null) {
                out.add(label(type) + " has no parameter " + e.getKey() + ".");
                continue;
            }
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (!r.canOverride() && !value.equalsIgnoreCase(r.defaultValue == null ? "" : r.defaultValue)) {
                out.add(r.label + " is fixed by the product at " + r.defaultValue + " and cannot be overridden.");
                continue;
            }
            switch (r.valueType) {
                case "NUMBER", "AMOUNT" -> {
                    BigDecimal n;
                    try {
                        n = new BigDecimal(value);
                    } catch (NumberFormatException ex) {
                        out.add(r.label + " must be a number.");
                        continue;
                    }
                    if (r.maxValue != null && n.compareTo(new BigDecimal(r.maxValue)) > 0) {
                        out.add(r.label + " of " + value + " is above the product's non-overridable maximum of "
                                + r.maxValue + ".");
                    } else if (r.minValue != null && n.compareTo(new BigDecimal(r.minValue)) < 0) {
                        out.add(r.label + " of " + value + " is below the product minimum of " + r.minValue + ".");
                    }
                }
                case "CHOICE" -> {
                    if (r.allowedValues != null && !List.of(r.allowedValues.split(",")).contains(value.toUpperCase(Locale.ROOT))) {
                        out.add(r.label + " must be one of " + r.allowedValues.replace(",", ", ") + ".");
                    }
                }
                default -> {
                    if (value.length() > 200) {
                        out.add(r.label + " is at most 200 characters.");
                    }
                }
            }
        }
        return out;
    }

    // ── versioned scheme and category terms ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> terms(String schemeRef) {
        security.authenticatedUser().validateHasPermissionTo("READ_SCHEME");
        records.scheme(schemeRef);
        return em.createQuery("select t from GroupBenefitTerms t where t.schemeRef = :r order by t.benefitType,"
                + " t.categoryCode, t.versionNo desc", BenefitTerms.class).setParameter("r", schemeRef).getResultList()
                .stream().map(this::termsView).toList();
    }

    /**
     * {benefitType, categoryCode?, included?, parameters {..}, effectiveFrom?, reason?}: a new version of the scheme's
     * (or one category's) terms for the benefit, validated against the product rules (AT-GL02-01) and waiting for a
     * second person's approval. Backdated changes need a reason and the underwriting authority, and record their
     * impact assessment (NS-GL02-006).
     */
    @Transactional
    public Map<String, Object> saveTerms(String schemeRef, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_SCHEME");
        GroupScheme scheme = records.lockedScheme(schemeRef);
        GroupRecords.requireOpen(scheme);
        String type = GroupRecords.requiredText(body, "benefitType", "Benefit", 30).toUpperCase(Locale.ROOT);
        if (!BENEFIT_TYPES.contains(type)) {
            throw new IllegalArgumentException("Benefit must be one of " + BENEFIT_TYPES.stream().sorted().toList());
        }
        String category = GroupRecords.text(body, "categoryCode");
        if (category != null) {
            category = category.toUpperCase(Locale.ROOT);
            String code = category;
            if (records.categories(scheme).stream().noneMatch(c -> c.getCode().equals(code))) {
                throw new IllegalArgumentException("Category " + code + " is not on scheme " + schemeRef + ".");
            }
        }
        JsonNode parameters = body.get("parameters");
        if (parameters != null && !parameters.isNull() && !parameters.isObject()) {
            throw new IllegalArgumentException("parameters must be an object of parameter codes and values.");
        }
        List<String> problems = violations(type, parameters);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        LocalDate today = LocalDate.now();
        String effectiveText = GroupRecords.text(body, "effectiveFrom");
        LocalDate effective = effectiveText == null ? today : GroupRecords.date(body, "effectiveFrom", "Effective date");
        String reason = GroupRecords.text(body, "reason");
        if (effective.isAfter(today)) {
            throw new IllegalArgumentException("A change applies from today; enter it on its effective date.");
        }
        if (effective.isBefore(scheme.getInceptionDate())) {
            throw new IllegalArgumentException("The effective date cannot be before the scheme's inception on "
                    + scheme.getInceptionDate() + ".");
        }
        if (effective.isBefore(today)) {
            // NS-GL02-006: a backdated change needs the underwriting authority and a reason, and records its impact.
            user.validateHasPermissionTo("UNDERWRITE_MEMBER");
            if (reason == null) {
                throw new IllegalArgumentException("A backdated change needs a reason (the authority for it).");
            }
        }
        if (reason != null && reason.length() > 500) {
            throw new IllegalArgumentException("Reason must be at most 500 characters.");
        }
        Integer latest = em.createQuery("select max(t.versionNo) from GroupBenefitTerms t where t.schemeRef = :r"
                        + " and t.benefitType = :b and ((:c is null and t.categoryCode is null) or t.categoryCode = :c)",
                        Integer.class)
                .setParameter("r", schemeRef).setParameter("b", type).setParameter("c", category).getSingleResult();
        em.createQuery("delete from GroupBenefitTerms t where t.schemeRef = :r and t.benefitType = :b"
                        + " and ((:c is null and t.categoryCode is null) or t.categoryCode = :c) and t.approvalStatus = 'PENDING'")
                .setParameter("r", schemeRef).setParameter("b", type).setParameter("c", category).executeUpdate();
        BenefitTerms t = new BenefitTerms();
        t.schemeRef = schemeRef;
        t.benefitType = type;
        t.categoryCode = category;
        t.versionNo = latest == null ? 1 : latest + 1;
        t.included = body.hasNonNull("included") && !body.get("included").asBoolean(true) ? "NO" : "YES";
        t.parameters = normalised(type, parameters);
        t.effectiveFrom = effective;
        t.approvalStatus = "PENDING";
        t.changeReason = reason;
        t.updatedBy = user.getUsername();
        t.updatedAt = LocalDateTime.now();
        t.impact = json(impact(scheme, type, category, effective));
        em.persist(t);
        return termsView(t);
    }

    /** What a change of the benefit's terms touches (NS-GL02-006): members and certificates in scope, open claims. */
    private Map<String, Object> impact(GroupScheme scheme, String type, String category, LocalDate effective) {
        List<SchemeMember> members = em.createQuery("select m from SchemeMember m join fetch m.category where m.scheme = :s"
                + " and m.status in :statuses", SchemeMember.class).setParameter("s", scheme)
                .setParameter("statuses", SchemeMember.ON_COVER).getResultList().stream()
                .filter(m -> category == null || m.getCategory().getCode().equals(category)).toList();
        long claims = em.createQuery("select count(c) from GroupClaim c where c.scheme = :s and c.benefitType = :b"
                + " and c.status in :open", Long.class).setParameter("s", scheme).setParameter("b", type)
                .setParameter("open", Set.of(GroupClaim.NOTIFIED, GroupClaim.APPROVED, GroupClaim.IN_PAYMENT))
                .getSingleResult();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("scope", category == null ? "Scheme" : "Category " + category);
        v.put("membersInScope", members.size());
        v.put("pendingUnderwriting", members.stream().filter(m -> SchemeMember.UNDERWRITING_PENDING.equals(m.getStatus()))
                .count());
        v.put("certificatesToReissue", members.stream().filter(m -> m.getAcceptedCover().signum() > 0).count());
        v.put("openClaims", claims);
        v.put("backdated", effective.isBefore(LocalDate.now()));
        List<String> notes = new ArrayList<>();
        notes.add(members.size() + " member(s) in scope; their certificates show the new terms once approved.");
        notes.add("Premium: unchanged by benefit terms (rates are set on the categories).");
        if (claims > 0) {
            notes.add(claims + " open " + label(type).toLowerCase(Locale.ROOT) + " claim(s) stay on the terms in force on"
                    + " their event date.");
        }
        if (effective.isBefore(LocalDate.now())) {
            notes.add("Backdated to " + effective + ": events from that date are assessed on the new terms.");
        }
        v.put("notes", notes);
        return v;
    }

    /**
     * {note}: approves every benefit change waiting on the scheme, the scheme benefit definitions and the versioned
     * terms alike; not by anyone who made one of them. Publishes SchemePlanApproved once.
     */
    @Transactional
    public List<Map<String, Object>> approvePlan(String schemeRef, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("UNDERWRITE_MEMBER");
        records.lockedScheme(schemeRef);
        String note = GroupRecords.requiredText(body, "note", "Approval note", 500);
        List<BenefitTerms> pending = em.createQuery("select t from GroupBenefitTerms t where t.schemeRef = :r"
                + " and t.approvalStatus = 'PENDING' order by t.benefitType, t.categoryCode", BenefitTerms.class)
                .setParameter("r", schemeRef).getResultList();
        List<Object[]> definitions = em.createQuery("select b.benefitType, b.updatedBy from SchemeBenefit b"
                + " where b.schemeRef = :r and b.approvalStatus = 'PENDING'", Object[].class)
                .setParameter("r", schemeRef).getResultList();
        if (pending.isEmpty() && definitions.isEmpty()) {
            throw new ConflictException(schemeRef + " has no benefit change waiting for approval.");
        }
        if (pending.stream().anyMatch(t -> user.getUsername().equalsIgnoreCase(t.updatedBy))
                || definitions.stream().anyMatch(d -> user.getUsername().equalsIgnoreCase((String) d[1]))) {
            throw new NoAuthorizationException("You changed this plan, so someone else must approve it.");
        }
        LocalDateTime now = LocalDateTime.now();
        for (BenefitTerms t : pending) {
            t.approvalStatus = "APPROVED";
            t.approvedBy = user.getUsername();
            t.approvedAt = now;
        }
        em.createQuery("update SchemeBenefit b set b.approvalStatus = 'APPROVED', b.approvedBy = :u, b.approvedAt = :t"
                + " where b.schemeRef = :r and b.approvalStatus = 'PENDING'").setParameter("u", user.getUsername())
                .setParameter("t", now).setParameter("r", schemeRef).executeUpdate();
        List<String> changed = new ArrayList<>();
        definitions.forEach(d -> changed.add((String) d[0]));
        pending.forEach(t -> changed.add(t.benefitType + (t.categoryCode == null ? "" : "/" + t.categoryCode) + " v"
                + t.versionNo));
        records.publish(Events.SCHEME_PLAN_APPROVED, schemeRef, Map.of("schemeRef", schemeRef, "benefits", changed,
                "approvedBy", user.getUsername(), "source", note), user.getUsername());
        audit.record(user.getUsername(), "Scheme plan approved", "Scheme", schemeRef, note + " (" + String.join(", ",
                changed) + ")");
        return terms(schemeRef);
    }

    // ── resolution ─────────────────────────────────────────────────────────────────────────────────

    /** The approved terms version in force on the date for the scheme (category null) or one category. */
    private BenefitTerms inForce(String schemeRef, String type, String category, LocalDate asAt) {
        return em.createQuery("select t from GroupBenefitTerms t where t.schemeRef = :r and t.benefitType = :b"
                        + " and ((:c is null and t.categoryCode is null) or t.categoryCode = :c)"
                        + " and t.approvalStatus = 'APPROVED' and t.effectiveFrom <= :d order by t.versionNo desc",
                        BenefitTerms.class)
                .setParameter("r", schemeRef).setParameter("b", type).setParameter("c", category).setParameter("d", asAt)
                .setMaxResults(1).getResultStream().findFirst().orElse(null);
    }

    /** Whether the category's members have the benefit (a category may leave out a scheme benefit, AT-GL01-02). */
    public boolean included(String schemeRef, String category, String type, LocalDate asAt) {
        BenefitTerms c = category == null ? null : inForce(schemeRef, type, category, asAt);
        if (c != null) {
            return "YES".equals(c.included);
        }
        BenefitTerms s = inForce(schemeRef, type, null, asAt);
        return s == null || "YES".equals(s.included);
    }

    /** The resolved value of every parameter for the category on the date: category, else scheme, else product. */
    public Map<String, String> resolved(String schemeRef, String category, String type, LocalDate asAt) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode scheme = parse(inForce(schemeRef, type, null, asAt));
        JsonNode cat = category == null ? null : parse(inForce(schemeRef, type, category, asAt));
        for (ProductRule r : rules(type)) {
            String v = value(cat, r.parameter);
            if (v == null) {
                v = value(scheme, r.parameter);
            }
            out.put(r.parameter, v == null ? r.defaultValue : v);
        }
        return out;
    }

    /**
     * The resolved rule set (NS-GL02-002, GL02 resolved-rules comparison): per category and benefit, every parameter's
     * product default, scheme value, category value, the resolved value and its source, beside pending changes.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> resolvedView(String schemeRef) {
        security.authenticatedUser().validateHasPermissionTo("READ_SCHEME");
        GroupScheme scheme = records.scheme(schemeRef);
        LocalDate today = LocalDate.now();
        List<String> benefits = em.createQuery("select b.benefitType from SchemeBenefit b where b.schemeRef = :r"
                + " order by b.benefitType", String.class).setParameter("r", schemeRef).getResultList();
        List<String> categories = new ArrayList<>();
        records.categories(scheme).forEach(c -> categories.add(c.getCode()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String type : benefits.isEmpty() ? List.of("GLA") : benefits) {
            List<ProductRule> rules = rules(type);
            JsonNode schemeTerms = parse(inForce(schemeRef, type, null, today));
            for (String category : categories) {
                JsonNode catTerms = parse(inForce(schemeRef, type, category, today));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("benefitType", type);
                row.put("category", category);
                row.put("included", included(schemeRef, category, type, today));
                List<Map<String, Object>> params = new ArrayList<>();
                for (ProductRule r : rules) {
                    String s = value(schemeTerms, r.parameter);
                    String c = value(catTerms, r.parameter);
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("parameter", r.parameter);
                    p.put("label", r.label);
                    p.put("productDefault", r.defaultValue);
                    p.put("maximum", r.maxValue);
                    p.put("overridable", r.canOverride());
                    p.put("schemeValue", s);
                    p.put("categoryValue", c);
                    p.put("resolved", c != null ? c : s != null ? s : r.defaultValue);
                    p.put("source", c != null ? "Category terms" : s != null ? "Scheme terms" : "Product default ("
                            + r.source + ")");
                    params.add(p);
                }
                row.put("parameters", params);
                rows.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemeRef", schemeRef);
        out.put("precedence", "Category terms override scheme terms, which override the product default; parameters the"
                + " product fixes cannot be overridden.");
        out.put("rows", rows);
        out.put("pending", em.createQuery("select t from GroupBenefitTerms t where t.schemeRef = :r"
                + " and t.approvalStatus = 'PENDING'", BenefitTerms.class).setParameter("r", schemeRef).getResultList()
                .stream().map(this::termsView).toList());
        return out;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    private String normalised(String type, JsonNode parameters) {
        ObjectNode out = mapper.createObjectNode();
        if (parameters != null && parameters.isObject()) {
            Map<String, ProductRule> rules = new LinkedHashMap<>();
            rules(type).forEach(r -> rules.put(r.parameter, r));
            parameters.fields().forEachRemaining(e -> {
                if (!e.getValue().isNull() && !e.getValue().asText().isBlank()) {
                    String v = e.getValue().asText().trim();
                    out.put(e.getKey(), "CHOICE".equals(rules.get(e.getKey()).valueType) ? v.toUpperCase(Locale.ROOT) : v);
                }
            });
        }
        return out.isEmpty() ? null : out.toString();
    }

    private JsonNode parse(BenefitTerms t) {
        if (t == null || t.parameters == null) {
            return null;
        }
        try {
            return mapper.readTree(t.parameters);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return null;
        }
    }

    private static String value(JsonNode node, String parameter) {
        return node == null || !node.hasNonNull(parameter) ? null : node.get(parameter).asText();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Map<String, Object> termsView(BenefitTerms t) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", t.id);
        v.put("benefitType", t.benefitType);
        v.put("categoryCode", t.categoryCode);
        v.put("version", t.versionNo);
        v.put("included", "YES".equals(t.included));
        v.put("parameters", parse(t));
        v.put("effectiveFrom", t.effectiveFrom);
        v.put("approvalStatus", t.approvalStatus);
        v.put("reason", t.changeReason);
        try {
            v.put("impact", t.impact == null ? null : mapper.readTree(t.impact));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            v.put("impact", null);
        }
        v.put("updatedBy", t.updatedBy);
        v.put("updatedAt", t.updatedAt);
        v.put("approvedBy", t.approvedBy);
        v.put("approvedAt", t.approvedAt);
        return v;
    }

    static String label(String code) {
        String t = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
    }
}
