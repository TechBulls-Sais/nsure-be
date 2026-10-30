package com.nsure.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Server-side validation of a policy's extra fields against one form version (guide §15): unknown keys are refused,
 * required fields must be present, and each value must match its declared type, length and allowed values. Returns
 * the normalised values; every problem is reported with its field path in one message.
 */
@Component
public class PolicyFieldValidator {

    private final ObjectMapper mapper;

    public PolicyFieldValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectNode validate(PolicyFormVersion form, JsonNode submitted) {
        return validate(form, submitted, true);
    }

    /** requireAll false checks what was given but lets required fields wait (a portal purchase staff complete later). */
    public ObjectNode validate(PolicyFormVersion form, JsonNode submitted, boolean requireAll) {
        ObjectNode clean = mapper.createObjectNode();
        List<String> problems = new ArrayList<>();
        if (submitted != null && !submitted.isNull() && !submitted.isObject()) {
            throw new IllegalArgumentException("customAttributes must be an object.");
        }
        Map<String, PolicyFieldDefinition> fields = form == null ? Map.of()
                : form.getFields().stream().collect(Collectors.toMap(PolicyFieldDefinition::getKey, f -> f));
        if (submitted != null && submitted.isObject()) {
            Iterator<String> names = submitted.fieldNames();
            while (names.hasNext()) {
                String key = names.next();
                if (!fields.containsKey(key)) {
                    problems.add("customAttributes." + key + ": not a field of this policy form");
                }
            }
        }
        for (PolicyFieldDefinition field : fields.values()) {
            JsonNode value = submitted == null ? null : submitted.get(field.getKey());
            String path = "customAttributes." + field.getKey();
            if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
                if (field.isRequired() && requireAll) {
                    problems.add(path + ": " + field.getLabel() + " is required");
                }
                continue;
            }
            String problem = check(field, value, clean);
            if (problem != null) {
                problems.add(path + ": " + problem);
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        return clean;
    }

    private static String check(PolicyFieldDefinition field, JsonNode value, ObjectNode clean) {
        String key = field.getKey();
        switch (field.getType()) {
            case "STRING" -> {
                if (!value.isTextual()) {
                    return "must be text";
                }
                String text = value.asText().trim();
                if (field.getMaxLength() != null && text.length() > field.getMaxLength()) {
                    return "at most " + field.getMaxLength() + " characters";
                }
                clean.put(key, text);
            }
            case "NUMBER" -> {
                try {
                    clean.put(key, new BigDecimal(value.asText().trim()));
                } catch (NumberFormatException ex) {
                    return "must be a number";
                }
            }
            case "DATE" -> {
                try {
                    clean.put(key, LocalDate.parse(value.asText().trim()).toString());
                } catch (DateTimeParseException ex) {
                    return "must be a date (yyyy-MM-dd)";
                }
            }
            case "BOOLEAN" -> {
                if (!value.isBoolean() && !Set.of("true", "false").contains(value.asText())) {
                    return "must be true or false";
                }
                clean.put(key, value.asBoolean() || "true".equals(value.asText()));
            }
            case "ENUM" -> {
                if (!field.getAllowedValues().contains(value.asText())) {
                    return "must be one of " + String.join(", ", field.getAllowedValues());
                }
                clean.put(key, value.asText());
            }
            default -> {
                return "has an unsupported type " + field.getType();
            }
        }
        return null;
    }
}
