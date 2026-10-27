package com.nsure.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CommandPayloadSanitizer {

    private static final Set<String> SECRET_KEYS = Set.of(
            "password",
            "newpassword",
            "currentpassword",
            "token",
            "accesstoken",
            "refreshtoken",
            "secret",
            "authorization",
            "cardnumber",
            "pan",
            "cvv",
            "nationalid",
            "nrc",
            "ssn");

    private final ObjectMapper mapper;
    private final int maxChars;

    public CommandPayloadSanitizer(
            ObjectMapper mapper,
            @Value("${nsure.audit.max-payload-chars:8000}") int maxChars) {
        this.mapper = mapper;
        this.maxChars = maxChars;
    }

    public SanitizeResult sanitize(String json) {
        String source = json == null || json.isBlank() ? "{}" : json;
        JsonNode tree;
        try {
            tree = mapper.readTree(source);
        } catch (Exception ex) {
            return new SanitizeResult("{\"unparsed\":true}", false, false, null);
        }
        boolean secrets = redact(tree);
        String text;
        try {
            text = mapper.writeValueAsString(tree);
        } catch (Exception ex) {
            return new SanitizeResult("{\"unparsed\":true}", secrets, false, null);
        }
        if (text.length() <= maxChars) {
            return new SanitizeResult(text, secrets, false, null);
        }
        String hash = sha256(text);
        String truncated = text.substring(0, maxChars);
        try {
            String stored = mapper.writeValueAsString(mapper.createObjectNode()
                    .put("truncated", true)
                    .put("sha256", hash)
                    .put("payload", truncated));
            return new SanitizeResult(stored, secrets, true, hash);
        } catch (Exception ex) {
            return new SanitizeResult(truncated, secrets, true, hash);
        }
    }

    private boolean redact(JsonNode node) {
        boolean redacted = false;
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (SECRET_KEYS.contains(name.toLowerCase(Locale.ROOT))) {
                    object.put(name, "***");
                    redacted = true;
                } else {
                    redacted = redact(object.get(name)) || redacted;
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) {
                redacted = redact(child) || redacted;
            }
        }
        return redacted;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record SanitizeResult(String json, boolean secretsRedacted, boolean truncated, String sha256) {
    }
}
