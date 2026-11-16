package com.nsure.config;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Shared environments must reach the database over TLS (NS-SH11-004). The prod profile refuses to start when the
 * JDBC URL does not demand an encrypted connection, so a copied "useSSL=false" URL cannot slip into UAT or
 * production.
 */
@Component
@Profile("prod")
public class DatabaseTransportGuard {

    private static final Pattern REQUIRES_TLS =
            Pattern.compile("[?&]sslmode=(required|verify_ca|verify_identity)(&|$)");

    public DatabaseTransportGuard(@Value("${spring.datasource.url}") String url) {
        check(url);
    }

    static void check(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:mysql:") && !REQUIRES_TLS.matcher(lower).find()) {
            throw new IllegalStateException("NSURE_DB_URL must require TLS: add sslMode=REQUIRED,"
                    + " or sslMode=VERIFY_IDENTITY with the server's CA.");
        }
    }
}
