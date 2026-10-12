package com.nsure.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.config.AuditProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CommandJsonSanitizer {

    public static final String REDACTED = "***";

    private static final Set<String> SECRET_KEYS = Set.of(
            "password",
            "passwd",
            "token",
            "accesstoken",
            "refreshtoken",
            "secret",
            "pin",
            "cardnumber",
            "cardno",
            "nationalid",
            "nationalidentification",
            "ssn",
            "cvv",
            "cvc",
            "authorization");

    private final ObjectMapper objectMapper;
    private final AuditProperties properties;

    public CommandJsonSanitizer(ObjectMapper objectMapper, AuditProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public String sanitize(String json) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        JsonNode tree;
        try {
            tree = objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return "{\"redacted\":true,\"reason\":\"unparsed payload\"}";
        }
        String rendered;
        try {
            rendered = objectMapper.writeValueAsString(redact(tree));
        } catch (JsonProcessingException ex) {
            return "{\"redacted\":true,\"reason\":\"unparsed payload\"}";
        }
        int max = properties.getMaxCommandJsonLength();
        if (max < 200 || max > 100_000) {
            throw new IllegalStateException("nsure.audit.max-command-json-length must be between 200 and 100000");
        }
        if (rendered.length() <= max) {
            return rendered;
        }
        return truncate(rendered, max);
    }

    private JsonNode redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode copy = object.deepCopy();
            List<String> names = new ArrayList<>();
            copy.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (isSecret(name)) {
                    copy.put(name, REDACTED);
                } else {
                    copy.set(name, redact(copy.get(name)));
                }
            }
            return copy;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = objectMapper.createArrayNode();
            for (JsonNode child : array) {
                copy.add(redact(child));
            }
            return copy;
        }
        return node;
    }

    private boolean isSecret(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return SECRET_KEYS.contains(normalized);
    }

    private String truncate(String rendered, int max) {
        String hash = sha256(rendered);
        int budget = Math.max(0, max - hash.length() - 80);
        String body = rendered.substring(0, Math.min(rendered.length(), budget));
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.put("truncated", true);
        wrapper.put("sha256", hash);
        wrapper.put("body", body);
        try {
            String wrapped = objectMapper.writeValueAsString(wrapper);
            if (wrapped.length() <= max) {
                return wrapped;
            }
        } catch (JsonProcessingException ignored) {
            // Fall through to the hash-only payload.
        }
        return "{\"truncated\":true,\"sha256\":\"" + hash + "\"}";
    }

    private static String sha256(String value) {
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte current : hashed) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
