package com.nsure.carecash;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Care Cash rule set (specification section 07, NS-SH05-006): every count, cap, percentage, waiting period and
 * interpretation the engine uses, as an effective-dated, approved version instead of code constants. A change is
 * proposed with a reason and approved by someone else; an event is assessed under the version in force on its
 * admission date, which its trace names. Version 1 holds DEMO DEFAULTS standing in for decision D04.
 */
@Service
public class CareCashRules {

    /** One value's schema: min and max (inclusive) or the allowed words. */
    record Def(String key, String label, Integer min, Integer max, Set<String> allowed) {
    }

    static final List<Def> DEFS = List.of(
            new Def("qualifyingNights", "Nights that qualify an admission", 1, 14, null),
            new Def("dayCasePercent", "Day case, % of the daily benefit", 0, 200, null),
            new Def("icuPercent", "ICU, % of the daily benefit (instead of the ward rate)", 100, 400, null),
            new Def("icuMaxDays", "ICU days per event", 0, 60, null),
            new Def("surgeryPercent", "Surgical benefit, % of the daily benefit, once per event", 0, 500, null),
            new Def("longStay1Nights", "First long-stay uplift after nights", 1, 60, null),
            new Def("longStay1Multiple", "First long-stay uplift, × daily benefit", 0, 20, null),
            new Def("longStay2Nights", "Second long-stay uplift after nights", 1, 120, null),
            new Def("longStay2Multiple", "Second long-stay uplift, × daily benefit", 0, 30, null),
            new Def("longStayBasis", "Long-stay threshold counts", null, null, Set.of("NIGHTS", "PAID_DAYS")),
            new Def("maternityMultiple", "Maternity, × daily benefit per live birth", 0, 20, null),
            new Def("maternityMaxClaims", "Maternity claims per life", 0, 10, null),
            new Def("maternityWaitMonths", "Maternity waiting period, months", 0, 36, null),
            new Def("illnessWaitMonths", "Illness waiting period, months", 0, 24, null),
            new Def("accidentWaitMonths", "Accident waiting period, months", 0, 24, null),
            new Def("preExistingWaitMonths", "Pre-existing condition exclusion, months", 0, 60, null),
            new Def("waiverAfterDays", "Premium waiver after consecutive admission days", 1, 60, null),
            new Def("waiverMonths", "Premium waiver, months", 0, 12, null),
            new Def("accidentalDeathAmount", "Accidental death benefit, BWP", 0, 1_000_000, null),
            new Def("accidentalDeathMinDaily", "Accidental death from daily tiers of, BWP", 0, 100_000, null),
            new Def("eventDayLimit", "Days per event", 1, 365, null),
            new Def("yearDayLimit", "Days per life per policy year", 1, 366, null),
            new Def("lifetimeDayLimit", "Days per life for life", 1, 5000, null),
            new Def("policyDayLimit", "Days across all lives of a policy", 1, 20000, null),
            new Def("readmissionDays", "A readmission within days shares the earlier event's limits", 0, 90, null),
            new Def("entryAgeMin", "Youngest entry age", 0, 100, null),
            new Def("entryAgeMax", "Oldest entry age", 0, 100, null),
            new Def("terminationAge", "Cover for a life ends at age", 18, 120, null),
            new Def("annualReset", "Year limits reset on", null, null, Set.of("COVER_ANNIVERSARY", "CALENDAR_YEAR")),
            new Def("multipleBirths", "Multiple births", null, null, Set.of("PER_BIRTH", "PER_EVENT")),
            new Def("accidentalDeathLimit", "Accidental death against day limits", null, null,
                    Set.of("SEPARATE", "AGGREGATE")),
            new Def("sharedLimitPriority", "Concurrent family admissions near a shared cap", null, null,
                    Set.of("FIRST_RECORDED")));

    /** A rule set version: effective from, the values, approval. */
    @Entity(name = "CareCashRuleSet")
    @Table(name = "cc_rule_set")
    public static class RuleSet {
        public static final String PROPOSED = "PROPOSED";
        public static final String APPROVED = "APPROVED";
        public static final String REJECTED = "REJECTED";

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "version_no", nullable = false, updatable = false)
        int versionNo;
        @Column(name = "effective_from", nullable = false, updatable = false)
        LocalDate effectiveFrom;
        @Column(nullable = false, length = 4000, updatable = false)
        String rules;
        @Column(length = 120, updatable = false)
        String label;
        @Column(nullable = false, length = 20)
        String status;
        @Column(length = 500, updatable = false)
        String reason;
        @Column(name = "proposed_by", nullable = false, length = 100, updatable = false)
        String proposedBy;
        @Column(name = "proposed_at", nullable = false, updatable = false)
        LocalDateTime proposedAt;
        @Column(name = "approved_by", length = 100)
        String approvedBy;
        @Column(name = "approved_at")
        LocalDateTime approvedAt;
        @Column(name = "rejection_reason", length = 500)
        String rejectionReason;
    }

    /** The values of one version, read by the engine. */
    public record Values(int version, String label, ObjectNode rules) {

        int i(String key) {
            return rules.path(key).asInt();
        }

        String s(String key) {
            return rules.path(key).asText();
        }
    }

    public record RuleSetView(int version, LocalDate effectiveFrom, String label, String status, JsonNode rules,
            String reason, String proposedBy, LocalDateTime proposedAt, String approvedBy, LocalDateTime approvedAt,
            String rejectionReason, boolean inForce) {
    }

    private final EntityManager em;
    private final ObjectMapper mapper;
    private final PlatformSecurityContext security;
    private final EvidenceChain evidence;

    public CareCashRules(EntityManager em, ObjectMapper mapper, PlatformSecurityContext security, EvidenceChain evidence) {
        this.em = em;
        this.mapper = mapper;
        this.security = security;
        this.evidence = evidence;
    }

    /** The approved version in force on {@code day} (the admission date). */
    public Values inForce(LocalDate day) {
        RuleSet set = em.createQuery("select r from CareCashRuleSet r where r.status = :approved and"
                        + " r.effectiveFrom <= :day order by r.effectiveFrom desc, r.versionNo desc", RuleSet.class)
                .setParameter("approved", RuleSet.APPROVED).setParameter("day", day).setMaxResults(1)
                .getResultStream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No approved Care Cash rule set is in force on " + day));
        return new Values(set.versionNo, set.label, read(set.rules));
    }

    @Transactional(readOnly = true)
    public List<RuleSetView> versions() {
        user("READ_CLAIM");
        List<RuleSet> all = all();
        LocalDate today = LocalDate.now();
        Integer live = all.stream().filter(r -> RuleSet.APPROVED.equals(r.status) && !r.effectiveFrom.isAfter(today))
                .max(java.util.Comparator.comparing((RuleSet r) -> r.effectiveFrom).thenComparingInt(r -> r.versionNo))
                .map(r -> r.versionNo).orElse(null);
        return all.stream().map(r -> new RuleSetView(r.versionNo, r.effectiveFrom, r.label, r.status, read(r.rules),
                r.reason, r.proposedBy, r.proposedAt, r.approvedBy, r.approvedAt, r.rejectionReason,
                live != null && live == r.versionNo)).toList();
    }

    /**
     * {effectiveFrom, reason, label?, rules: {key: value}}: the next version. Unset values carry over from the version
     * in force on its start; every value is checked against its range; it cannot start in the past.
     */
    @Transactional
    public RuleSetView propose(JsonNode body) {
        AppUser user = user("UPDATE_PRODUCT");
        if (all().stream().anyMatch(r -> RuleSet.PROPOSED.equals(r.status))) {
            throw new ConflictException("A Care Cash rule change is already awaiting approval.");
        }
        LocalDate from;
        try {
            from = body.hasNonNull("effectiveFrom") ? LocalDate.parse(body.get("effectiveFrom").asText()) : LocalDate.now();
        } catch (java.time.format.DateTimeParseException ex) {
            throw new com.nsure.common.exception.InvalidFieldException("effectiveFrom", "effectiveFrom must be a date.");
        }
        if (from.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("A rule change cannot start in the past: events already assessed keep"
                    + " the rules they were assessed under.");
        }
        String reason = body.path("reason").asText("").trim();
        if (reason.length() < 5) {
            throw new IllegalArgumentException("Give the reason for the rule change (the decision it implements).");
        }
        ObjectNode values = inForce(from).rules().deepCopy();
        List<String> errors = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = body.path("rules").fields();
        Map<String, Def> defs = new LinkedHashMap<>();
        DEFS.forEach(d -> defs.put(d.key(), d));
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> f = fields.next();
            Def def = defs.get(f.getKey());
            if (def == null) {
                errors.add("'" + f.getKey() + "' is not a Care Cash rule.");
            } else if (def.allowed() != null) {
                if (!def.allowed().contains(f.getValue().asText())) {
                    errors.add(def.label() + " must be one of " + String.join(", ", def.allowed()) + ".");
                } else {
                    values.put(def.key(), f.getValue().asText());
                }
            } else if (!f.getValue().canConvertToInt() && !f.getValue().asText().matches("\\d+")) {
                errors.add(def.label() + " must be a whole number.");
            } else {
                int n = f.getValue().asInt(Integer.parseInt(f.getValue().asText("0")));
                if (n < def.min() || n > def.max()) {
                    errors.add(def.label() + " must be between " + def.min() + " and " + def.max() + ".");
                } else {
                    values.put(def.key(), n);
                }
            }
        }
        if (values.path("longStay2Nights").asInt() <= values.path("longStay1Nights").asInt()) {
            errors.add("The second long-stay threshold must be after the first.");
        }
        if (values.path("entryAgeMin").asInt() > values.path("entryAgeMax").asInt()
                || values.path("terminationAge").asInt() <= values.path("entryAgeMax").asInt()) {
            errors.add("Entry ages must be in order and cover must end after the oldest entry age.");
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", errors));
        }
        RuleSet set = new RuleSet();
        set.versionNo = all().stream().mapToInt(r -> r.versionNo).max().orElse(0) + 1;
        set.effectiveFrom = from;
        set.rules = write(values);
        set.label = body.hasNonNull("label") ? clip(body.get("label").asText(), 120) : "Proposed by " + user.getUsername();
        set.status = RuleSet.PROPOSED;
        set.reason = clip(reason, 500);
        set.proposedBy = user.getUsername();
        set.proposedAt = LocalDateTime.now();
        em.persist(set);
        evidence.record(user.getUsername(), "Proposed Care Cash rules", "CareCashRuleSet", "v" + set.versionNo,
                reason + " (from " + from + ")");
        return view(set);
    }

    @Transactional
    public RuleSetView decide(int version, boolean approve, String reason) {
        AppUser user = user("ACTIVATE_PRODUCT");
        RuleSet set = all().stream().filter(r -> r.versionNo == version).findFirst()
                .orElseThrow(() -> new NotFoundException("Care Cash rules v" + version + " not found"));
        if (!RuleSet.PROPOSED.equals(set.status)) {
            throw new ConflictException("Care Cash rules v" + version + " is " + set.status.toLowerCase() + ".");
        }
        if (user.getUsername().equalsIgnoreCase(set.proposedBy)) {
            throw new NoAuthorizationException("You proposed these rules, so someone else must decide them.");
        }
        if (approve && set.effectiveFrom.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("v" + version + " was to start on " + set.effectiveFrom
                    + ", which has passed. Reject it and propose a version starting today or later.");
        }
        if (!approve && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("Give the reason for rejecting the rules.");
        }
        set.status = approve ? RuleSet.APPROVED : RuleSet.REJECTED;
        set.approvedBy = user.getUsername();
        set.approvedAt = LocalDateTime.now();
        set.rejectionReason = approve ? null : clip(reason, 500);
        evidence.record(user.getUsername(), approve ? "Approved Care Cash rules" : "Rejected Care Cash rules",
                "CareCashRuleSet", "v" + version, approve ? "In force from " + set.effectiveFrom : reason);
        return view(set);
    }

    private RuleSetView view(RuleSet r) {
        return new RuleSetView(r.versionNo, r.effectiveFrom, r.label, r.status, read(r.rules), r.reason, r.proposedBy,
                r.proposedAt, r.approvedBy, r.approvedAt, r.rejectionReason, false);
    }

    private List<RuleSet> all() {
        return em.createQuery("select r from CareCashRuleSet r order by r.versionNo desc", RuleSet.class)
                .getResultList();
    }

    private ObjectNode read(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return node instanceof ObjectNode o ? o : mapper.createObjectNode();
        } catch (JsonProcessingException ex) {
            return mapper.createObjectNode();
        }
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
