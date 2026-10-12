package com.nsure.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class CommandJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CommandJson() {
    }

    public static JsonNode parse(String json) {
        try {
            JsonNode node = MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("Command body must be a JSON object");
            }
            return node;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Command body must be a JSON object");
        }
    }

    public static String requiredText(JsonNode body, String field) {
        String value = optionalText(body, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public static String optionalText(JsonNode body, String field) {
        if (body == null || !body.has(field) || body.get(field).isNull()) {
            return null;
        }
        JsonNode node = body.get(field);
        if (!node.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return node.asText();
    }

    public static Boolean optionalBoolean(JsonNode body, String field) {
        if (body == null || !body.has(field) || body.get(field).isNull()) {
            return null;
        }
        if (!body.get(field).isBoolean()) {
            throw new IllegalArgumentException(field + " must be true or false");
        }
        return body.get(field).asBoolean();
    }

    public static Long optionalLong(JsonNode body, String field) {
        if (body == null || !body.has(field) || body.get(field).isNull()) {
            return null;
        }
        if (!body.get(field).isIntegralNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        return body.get(field).asLong();
    }

    public static Long requiredLong(JsonNode body, String field) {
        Long value = optionalLong(body, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
