package com.nsure.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CommandPayloadSanitizerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void redactsSecretsAndKeepsOtherFields() {
        CommandPayloadSanitizer sanitizer = new CommandPayloadSanitizer(mapper, 8000);

        CommandPayloadSanitizer.SanitizeResult result = sanitizer.sanitize("""
                {"username":"ada","password":"secret","profile":{"token":"abc","city":"Lusaka"}}
                """);

        assertThat(result.secretsRedacted()).isTrue();
        assertThat(result.truncated()).isFalse();
        assertThat(result.json()).contains("\"username\":\"ada\"");
        assertThat(result.json()).contains("\"city\":\"Lusaka\"");
        assertThat(result.json()).contains("\"password\":\"***\"");
        assertThat(result.json()).contains("\"token\":\"***\"");
        assertThat(result.json()).doesNotContain("secret");
        assertThat(result.json()).doesNotContain("abc");
    }

    @Test
    void truncatesLargePayloadsAndKeepsAHash() {
        CommandPayloadSanitizer sanitizer = new CommandPayloadSanitizer(mapper, 30);

        CommandPayloadSanitizer.SanitizeResult result = sanitizer.sanitize("{\"note\":\"" + "x".repeat(100) + "\"}");

        assertThat(result.truncated()).isTrue();
        assertThat(result.sha256()).isNotBlank();
        assertThat(result.json()).contains("\"truncated\":true");
        assertThat(result.json()).contains(result.sha256());
    }
}
