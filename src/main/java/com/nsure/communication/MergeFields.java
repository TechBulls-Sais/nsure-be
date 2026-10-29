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

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");

    private static final Map<String, List<String>> BY_EVENT = new LinkedHashMap<>();

    static {
        BY_EVENT.put(Events.POLICY_ISSUED, List.of("policyRef", "customerName", "product", "sumAssured", "premium"));
        BY_EVENT.put(Events.PREMIUM_RECEIVED,
                List.of("policyRef", "customerName", "product", "amount", "method", "receiptRef"));
        BY_EVENT.put(Events.CLAIM_PAID,
                List.of("claimRef", "policyRef", "customerName", "product", "amount", "payoutRef"));
        BY_EVENT.put(Events.SERVICE_CASE_OPENED, List.of("caseRef", "caseType", "customerName", "category"));
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

    static List<String> unknown(String eventType, String... texts) {
        List<String> known = known(eventType);
        return placeholders(texts).stream().filter(field -> !known.contains(field)).toList();
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
