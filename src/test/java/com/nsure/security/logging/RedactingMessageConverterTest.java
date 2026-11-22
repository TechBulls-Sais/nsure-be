package com.nsure.security.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** NS-SH11-004: personal identifiers never reach the log in clear. */
class RedactingMessageConverterTest {

    @Test
    void emailsPhonesIdentityNumbersAndTokensAreMasked() {
        String out = RedactingMessageConverter.redact("Message MSG-000012 to kagiso.molefe@example.com at"
                + " +267 71 234 501 for Omang 880618404, passport BW0950122, Authorization: Bearer abcDEF123456789xyz");
        assertThat(out).doesNotContain("kagiso.molefe", "71 234", "880618404", "BW0950122", "abcDEF123456789xyz")
                .contains("ka***@example.com", "***501", "******404", "BW*****22", "Bearer [redacted]", "MSG-000012");
        // References and amounts stay readable.
        assertThat(RedactingMessageConverter.redact("Policy POL-00042 premium BWP 150.00 on 2026-11-22"))
                .isEqualTo("Policy POL-00042 premium BWP 150.00 on 2026-11-22");
    }
}
