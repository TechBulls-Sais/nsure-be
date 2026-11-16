package com.nsure.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseTransportGuardTest {

    @Test
    void aSharedEnvironmentMustReachMySqlOverTls() {
        assertThatCode(() -> DatabaseTransportGuard.check(
                "jdbc:mysql://db:3306/nsure?sslMode=REQUIRED&serverTimezone=UTC")).doesNotThrowAnyException();
        assertThatCode(() -> DatabaseTransportGuard.check(
                "jdbc:mysql://db:3306/nsure?serverTimezone=UTC&sslMode=VERIFY_IDENTITY")).doesNotThrowAnyException();
        assertThatThrownBy(() -> DatabaseTransportGuard.check(
                "jdbc:mysql://db:3306/nsure?useSSL=false&serverTimezone=UTC"))
                .hasMessageContaining("must require TLS");
        assertThatThrownBy(() -> DatabaseTransportGuard.check(
                "jdbc:mysql://db:3306/nsure?sslMode=PREFERRED")).hasMessageContaining("must require TLS");
        assertThatThrownBy(() -> DatabaseTransportGuard.check("jdbc:mysql://db:3306/nsure"))
                .hasMessageContaining("must require TLS");
    }
}
