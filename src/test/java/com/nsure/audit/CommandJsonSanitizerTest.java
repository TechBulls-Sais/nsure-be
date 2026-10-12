package com.nsure.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.config.AuditProperties;
import org.junit.jupiter.api.Test;

class CommandJsonSanitizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void redactsSecretsAndKeepsOtherFields() throws Exception {
        CommandJsonSanitizer sanitizer = sanitizer(8000);
        String sanitized = sanitizer.sanitize("""
                {"username":"jane","password":"secret-value","profile":{"card_number":"4111111111111111","city":"Gaborone"}}
                """);

        var json = objectMapper.readTree(sanitized);
        assertThat(json.get("username").asText()).isEqualTo("jane");
        assertThat(json.get("password").asText()).isEqualTo("***");
        assertThat(json.get("profile").get("card_number").asText()).isEqualTo("***");
        assertThat(json.get("profile").get("city").asText()).isEqualTo("Gaborone");
        assertThat(sanitized).doesNotContain("secret-value").doesNotContain("4111111111111111");
    }

    @Test
    void truncatesOversizedPayloads() {
        CommandJsonSanitizer sanitizer = sanitizer(200);
        String sanitized = sanitizer.sanitize("{\"note\":\"" + "a".repeat(500) + "\"}");

        assertThat(sanitized).contains("\"truncated\":true");
        assertThat(sanitized).contains("sha256");
        assertThat(sanitized.length()).isLessThanOrEqualTo(200);
    }

    @Test
    void unparsedPayloadIsNotStored() {
        CommandJsonSanitizer sanitizer = sanitizer(8000);
        assertThat(sanitizer.sanitize("password=plain-text")).isEqualTo("{\"redacted\":true,\"reason\":\"unparsed payload\"}");
    }

    private static CommandJsonSanitizer sanitizer(int max) {
        AuditProperties properties = new AuditProperties();
        properties.setMaxCommandJsonLength(max);
        return new CommandJsonSanitizer(new ObjectMapper(), properties);
    }
}
