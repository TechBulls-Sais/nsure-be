package com.nsure.communication;

import com.nsure.integration.Events;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The merge fields a template may use per event type (the event payload fields, plus the recipient's customer
 * reference and the secure portal link), and the renderer that fills {@code {{field}}} placeholders.
 */
final class MergeFields {

    static final String CUSTOMER_REF = "customerRef";
    static final String PORTAL_LINK = "portalLink";
    static final String INTERMEDIARY_REF = "intermediaryRef";
    static final String INTERMEDIARY_NAME = "intermediaryName";
    static final String LENDER_NAME = "lenderName";

    /**
     * NS-SH09B-001/003: what an intermediary-audience template may show. References, the product and the customer's
     * name only: never amounts, reasons or other detail an agent should read in the secure portal.
     */
    private static final Set<String> INTERMEDIARY_SAFE = Set.of("policyRef", "claimRef", "caseRef", "product",
            "customerName", "dueDate");

    /** Fields an intermediary may also see for an event about itself: its own commission statement. */
    private static final Map<String, Set<String>> INTERMEDIARY_OWN = Map.of(
            Events.COMMISSION_PAID, Set.of("statementRef", "amount"));

    /** What a lender-audience template may show: its own loan references, never borrower health or claim detail. */
    private static final Set<String> LENDER_SAFE = Set.of("loanRef", "lenderRef", "lenderLoanId", "policyRef",
            "disbursementDate");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");

    private static final Map<String, List<String>> BY_EVENT = new LinkedHashMap<>();

    static {
        BY_EVENT.put(Events.POLICY_ISSUED, List.of("policyRef", "customerName", "product", "sumAssured", "premium"));
        BY_EVENT.put(Events.PREMIUM_RECEIVED,
                List.of("policyRef", "customerName", "product", "amount", "method", "receiptRef"));
        BY_EVENT.put(Events.CLAIM_PAID,
                List.of("claimRef", "policyRef", "customerName", "product", "amount", "payoutRef"));
        BY_EVENT.put(Events.CLAIM_RECEIVED, List.of("claimRef", "policyRef", "customerName", "product", "amount"));
        BY_EVENT.put(Events.CLAIM_APPROVED, List.of("claimRef", "policyRef", "customerName", "product", "amount"));
        BY_EVENT.put(Events.CLAIM_REJECTED,
                List.of("claimRef", "policyRef", "customerName", "product", "amount", "reason"));
        BY_EVENT.put(Events.SERVICE_CASE_OPENED, List.of("caseRef", "caseType", "customerName", "category"));
        BY_EVENT.put(Events.PREMIUM_DUE_REMINDER,
                List.of("policyRef", "customerName", "product", "amount", "dueDate"));
        BY_EVENT.put(Events.OPERATIONAL_NOTICE, List.of("customerName"));
        // NS-SH09B-001: missing evidence, commission statements and lender notices. The evidence requirement itself
        // (possibly medical) is never a merge field; the customer reads it in the secure portal.
        BY_EVENT.put(Events.EVIDENCE_REQUESTED, List.of("caseRef", "applicationRef", "customerName", "dueDate"));
        BY_EVENT.put(Events.COMMISSION_PAID, List.of("statementRef", "intermediaryRef", "amount"));
        BY_EVENT.put(Events.LOAN_DISBURSED,
                List.of("loanRef", "lenderRef", "lenderLoanId", "policyRef", "customerName", "disbursementDate"));
    }

    private MergeFields() {
    }

    /** The event types a template can be written for. */
    static Set<String> eventTypes() {
        return BY_EVENT.keySet();
    }

    static List<String> known(String eventType) {
        List<String> fields = new ArrayList<>(BY_EVENT.getOrDefault(eventType, List.of()));
        fields.add(CUSTOMER_REF);
        fields.add(PORTAL_LINK);
        return fields;
    }

    /** The fields a template for this audience may use; an intermediary gets references only (see INTERMEDIARY_SAFE). */
    static List<String> known(String eventType, String audience) {
        if (CommunicationService.LENDER.equals(audience)) {
            List<String> fields = new ArrayList<>(BY_EVENT.getOrDefault(eventType, List.of()).stream()
                    .filter(LENDER_SAFE::contains).toList());
            fields.add(LENDER_NAME);
            fields.add(PORTAL_LINK);
            return fields;
        }
        if (!CommunicationService.INTERMEDIARY.equals(audience)) {
            return known(eventType);
        }
        Set<String> own = INTERMEDIARY_OWN.getOrDefault(eventType, Set.of());
        List<String> fields = new ArrayList<>(BY_EVENT.getOrDefault(eventType, List.of()).stream()
                .filter(f -> INTERMEDIARY_SAFE.contains(f) || own.contains(f)).filter(f -> !INTERMEDIARY_REF.equals(f))
                .toList());
        fields.add(INTERMEDIARY_REF);
        fields.add(INTERMEDIARY_NAME);
        fields.add(PORTAL_LINK);
        return fields;
    }

    /** The placeholders in the texts that a template for this event and audience may not use. */
    static List<String> unknownFor(String eventType, String audience, String... texts) {
        List<String> known = known(eventType, audience);
        return placeholders(texts).stream().filter(field -> !known.contains(field)).toList();
    }

    /** Sample values for a template preview: the field name in brackets, or a realistic example. */
    static String sample(String field) {
        return switch (field) {
            case "policyRef" -> "POL-000123";
            case "claimRef" -> "CLM-000045";
            case "caseRef" -> "SR-000067";
            case "customerName" -> "Kagiso Molefe";
            case CUSTOMER_REF -> "CUS-1001";
            case "product" -> "Term Life";
            case "amount", "sumAssured", "premium" -> "BWP 1,000.00";
            case "dueDate" -> "2026-12-01";
            case INTERMEDIARY_REF -> "INT-00001";
            case INTERMEDIARY_NAME -> "Example Brokers";
            default -> "[" + field + "]";
        };
    }

    static List<String> placeholders(String... texts) {
        Set<String> found = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher matcher = PLACEHOLDER.matcher(text);
            while (matcher.find()) {
                found.add(matcher.group(1));
            }
        }
        return List.copyOf(found);
    }


    /** Replaces every placeholder with its value; a missing value renders as empty text. */
    static String render(String text, Function<String, String> values) {
        if (text == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.apply(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
