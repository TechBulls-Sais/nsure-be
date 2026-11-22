package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The typed rules a product configuration may carry (NS-SH02-001, NS-SH02-005). Every rule has a schema — type, unit,
 * range or allowed values — and an owner (the function that signs it off). Rules are values only: an unknown key, a
 * value of the wrong type or out of range, a broken dependency or a prohibited combination is refused, and nothing a
 * business user enters is ever executed. The release checklist (AT-SH02-01) requires the refund and termination rules.
 *
 * <p>The ranges and prohibited combinations are DEMO DEFAULTS standing in for decisions D02, D03 and D05.
 */
public final class ProductRules {

    /** One rule's schema. {@code type}: INT, MONEY, ENUM or LIST. */
    public record RuleDef(String key, String label, String type, String unit, Integer min, Integer max,
            List<String> allowed, String owner, String releaseGroup, String help) {
    }

    public static final List<RuleDef> CATALOG = List.of(
            new RuleDef("ageBasis", "Age basis", "ENUM", null, null, null,
                    List.of("LAST_BIRTHDAY", "NEAREST_BIRTHDAY", "NEXT_BIRTHDAY"), "Actuarial", "Calculation",
                    "How a life's age is counted for entry ages and rates."),
            new RuleDef("termYears", "Policy term", "INT", "years", 0, 50, null, "Actuarial", null,
                    "0 means whole of life."),
            new RuleDef("cessationAge", "Cover ceases at age", "INT", "years", 50, 100, null, "Underwriting",
                    "Termination", "Cover ends at this age even within the term."),
            new RuleDef("lapseAfterMissedPremiums", "Lapse after missed premiums", "INT", "premiums", 1, 6, null,
                    "Operations", "Termination", "Unpaid premiums, after grace, before the policy lapses."),
            new RuleDef("reinstatementMonths", "Reinstatement window", "INT", "months", 0, 36, null, "Operations", null,
                    "How long after lapse a policy may be reinstated."),
            new RuleDef("reinstatementEvidence", "Reinstatement evidence", "ENUM", null, null, null,
                    List.of("NONE", "HEALTH_DECLARATION", "FULL_UNDERWRITING"), "Underwriting", null,
                    "What a reinstatement needs."),
            new RuleDef("coolingOffDays", "Cooling-off period", "INT", "days", 0, 60, null, "Compliance", "Refund",
                    "Days after issue in which the customer may cancel."),
            new RuleDef("refundOnCancellation", "Refund on cancellation", "ENUM", null, null, null,
                    List.of("NO_REFUND", "FULL_WITHIN_COOLING_OFF", "PRO_RATA_UNEARNED"), "Finance", "Refund",
                    "What is refunded when the policy is cancelled."),
            new RuleDef("maxCoverWithoutEvidence", "Cover without medical evidence", "MONEY", "BWP", 0, 10_000_000,
                    null, "Underwriting", null, "Above this, cover needs manual underwriting."),
            new RuleDef("maxBeneficiaries", "Most beneficiaries", "INT", "people", 1, 20, null, "Legal", null,
                    "How many beneficiaries a policy may name."),
            new RuleDef("riders", "Riders", "LIST", null, null, null,
                    List.of("ACCIDENTAL_DEATH", "WAIVER_OF_PREMIUM", "CASHBACK", "RETURN_OF_PREMIUM"), "Product", null,
                    "Optional benefits sold with the plan."),
            new RuleDef("claimEvidence", "Claim evidence", "LIST", null, null, null,
                    List.of("DEATH_CERTIFICATE", "ID_DOCUMENT", "MEDICAL_REPORT", "HOSPITAL_INVOICE", "POLICE_REPORT"),
                    "Claims", null, "Documents a claim on this product needs."));

    private static final Map<String, RuleDef> BY_KEY = new LinkedHashMap<>();

    static {
        CATALOG.forEach(def -> BY_KEY.put(def.key(), def));
    }

    private ProductRules() {
    }

    /** Parses stored rules; blank is an empty set. */
    static ObjectNode parse(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode node = mapper.readTree(json);
            return node instanceof ObjectNode object ? object : mapper.createObjectNode();
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return mapper.createObjectNode();
        }
    }

    /**
     * Cleans the rules a request sends: unknown keys and malformed values are refused with every problem at once.
     * Null or empty values are dropped (the rule is simply not set).
     */
    static ObjectNode clean(ObjectMapper mapper, JsonNode rules) {
        ObjectNode out = mapper.createObjectNode();
        if (rules == null || rules.isNull()) {
            return out;
        }
        if (!rules.isObject()) {
            throw new IllegalArgumentException("rules must be an object of rule values.");
        }
        List<String> errors = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = rules.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            RuleDef def = BY_KEY.get(field.getKey());
            JsonNode value = field.getValue();
            if (def == null) {
                errors.add("'" + field.getKey() + "' is not a product rule. Rules are values from the rule catalogue;"
                        + " nothing entered here is executed.");
                continue;
            }
            if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())
                    || (value.isArray() && value.isEmpty())) {
                continue;
            }
            switch (def.type()) {
                case "INT", "MONEY" -> {
                    if (!value.canConvertToLong() && !(value.isTextual() && value.asText().trim().matches("-?\\d+"))) {
                        errors.add(def.label() + " must be a whole number" + unit(def) + ".");
                        continue;
                    }
                    long n = value.isTextual() ? Long.parseLong(value.asText().trim()) : value.asLong();
                    if ((def.min() != null && n < def.min()) || (def.max() != null && n > def.max())) {
                        errors.add(def.label() + " must be between " + def.min() + " and " + def.max() + unit(def) + ".");
                        continue;
                    }
                    out.put(def.key(), n);
                }
                case "ENUM" -> {
                    String v = value.asText().trim();
                    if (!def.allowed().contains(v)) {
                        errors.add(def.label() + " must be one of " + String.join(", ", def.allowed()) + ".");
                        continue;
                    }
                    out.put(def.key(), v);
                }
                case "LIST" -> {
                    if (!value.isArray()) {
                        errors.add(def.label() + " must be a list.");
                        continue;
                    }
                    ArrayNode list = out.putArray(def.key());
                    for (JsonNode item : value) {
                        String v = item.asText().trim();
                        if (!def.allowed().contains(v)) {
                            errors.add(def.label() + ": '" + v + "' is not one of " + String.join(", ", def.allowed()) + ".");
                        } else {
                            list.add(v);
                        }
                    }
                }
                default -> errors.add(def.label() + " has an unknown type.");
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", errors));
        }
        return out;
    }

    /**
     * Dependencies and prohibited combinations across the rules and the configuration's own settings (NS-SH02-005).
     * Empty when the combination is allowed.
     */
    static List<String> conflicts(ObjectNode rules, ProductConfiguration row) {
        List<String> problems = new ArrayList<>();
        if (row.getMinAge() < 0 || row.getMaxAge() > 100 || row.getMinAge() > row.getMaxAge()) {
            problems.add("Entry ages must be between 0 and 100, the minimum no higher than the maximum.");
        }
        if (row.getCoverAmount() <= 0 || row.getPremiumAmount() <= 0) {
            problems.add("Cover and premium must be greater than zero.");
        }
        if (row.getWaitingMonths() < 0 || row.getWaitingMonths() > 36) {
            problems.add("The waiting period must be 0 to 36 months.");
        }
        if (row.getGraceDays() < 0 || row.getGraceDays() > 90) {
            problems.add("The grace period must be 0 to 90 days.");
        }
        if (!Set.of("Monthly", "Quarterly", "Annually").contains(row.getFrequency())) {
            problems.add("Premium frequency must be Monthly, Quarterly or Annually.");
        }
        Integer cessation = intRule(rules, "cessationAge");
        Integer term = intRule(rules, "termYears");
        if (cessation != null && cessation <= row.getMaxAge()) {
            problems.add("Cover ceases at " + cessation + ", which is not after the oldest entry age (" + row.getMaxAge()
                    + "): someone entering at that age would have no cover.");
        }
        if (cessation != null && term != null && term > 0 && row.getMinAge() + term > cessation + 30) {
            problems.add("A " + term + "-year term from the youngest entry age runs far past cessation at " + cessation
                    + "; shorten the term or raise the cessation age.");
        }
        List<String> riders = list(rules, "riders");
        if (riders.contains("RETURN_OF_PREMIUM") && (term == null || term == 0)) {
            problems.add("Return of premium needs a survival date: set a policy term (whole of life has none, D05).");
        }
        if (riders.contains("RETURN_OF_PREMIUM") && riders.contains("CASHBACK")) {
            problems.add("Cashback and return of premium together are not an approved combination (demo rule, D05).");
        }
        if ("FULL_WITHIN_COOLING_OFF".equals(text(rules, "refundOnCancellation"))
                && (intRule(rules, "coolingOffDays") == null || intRule(rules, "coolingOffDays") == 0)) {
            problems.add("A full refund within cooling-off needs a cooling-off period of at least one day.");
        }
        Integer evidenceLimit = intRule(rules, "maxCoverWithoutEvidence");
        if (evidenceLimit != null && !"Manual review".equals(row.getUnderwriting())
                && row.getCoverAmount() > evidenceLimit) {
            problems.add(row.getUnderwriting() + " cannot accept cover of " + row.getCoverAmount() + " above the "
                    + evidenceLimit + " evidence limit: choose manual review or lower the cover.");
        }
        Integer reinstatement = intRule(rules, "reinstatementMonths");
        if (reinstatement != null && reinstatement > 6 && "NONE".equals(text(rules, "reinstatementEvidence"))) {
            problems.add("Reinstating more than 6 months after lapse needs at least a health declaration.");
        }
        if (row.getBenefits() != null && row.getBenefits().contains("Hospital cash")
                && riders.contains("RETURN_OF_PREMIUM")) {
            problems.add("Hospital cash plans do not carry a return of premium rider (demo rule).");
        }
        return problems;
    }

    /** Release rules that are not set: the refund and termination rules every product must have (AT-SH02-01). */
    static List<String> missingForRelease(ObjectNode rules, String group) {
        List<String> missing = new ArrayList<>();
        for (RuleDef def : CATALOG) {
            if (group.equals(def.releaseGroup()) && !rules.hasNonNull(def.key())) {
                missing.add(def.label() + " (" + def.owner() + ")");
            }
        }
        return missing;
    }

    /** A life's age on {@code on} under the configured age basis (last birthday when none is set). */
    static int age(String basis, LocalDate dateOfBirth, LocalDate on) {
        int last = Period.between(dateOfBirth, on).getYears();
        if ("NEXT_BIRTHDAY".equals(basis)) {
            return last + 1;
        }
        if ("NEAREST_BIRTHDAY".equals(basis)) {
            LocalDate lastBirthday = dateOfBirth.plusYears(last);
            return lastBirthday.plusMonths(6).isAfter(on) ? last : last + 1;
        }
        return last;
    }

    static Integer intRule(JsonNode rules, String key) {
        return rules.hasNonNull(key) && rules.get(key).canConvertToInt() ? rules.get(key).asInt() : null;
    }

    static String text(JsonNode rules, String key) {
        return rules.hasNonNull(key) ? rules.get(key).asText() : null;
    }

    static List<String> list(JsonNode rules, String key) {
        List<String> out = new ArrayList<>();
        if (rules.has(key) && rules.get(key).isArray()) {
            rules.get(key).forEach(item -> out.add(item.asText()));
        }
        return out;
    }

    private static String unit(RuleDef def) {
        return def.unit() == null ? "" : " " + def.unit();
    }
}
