package com.nsure.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Signed payment callbacks (spec §10 POST /payment-notifications, AT-SH12-01). A provider-specific callback is
 * authenticated by {@code X-Nsure-Signature: sha256=<hex HMAC-SHA256 of "timestamp.body">} with this insurer's callback
 * secret and {@code X-Nsure-Timestamp} (epoch seconds) inside the replay window, then mapped to the canonical
 * notification and processed once per provider transaction: a repeated callback is acknowledged with the first result
 * and causes no second business effect. No real provider exists yet, so {@link #simulate} plays the provider: it signs
 * and sends a callback through exactly the same verification (optionally twice, or tampered after signing).
 *
 * <p>It also runs the NS-SH12-004 sweep: confirmations whose provider status query timed out are asked again.
 */
@Service
public class PaymentCallbacks {

    private static final Logger log = LoggerFactory.getLogger(PaymentCallbacks.class);

    private final PaymentNotifications notifications;
    private final ProviderStatusClient providerStatus;
    private final PlatformSecurityContext security;
    private final ObjectMapper mapper;
    private final OperatorActionLog actions;
    private final byte[] secret;
    private final long windowSeconds;
    private final boolean simulator;

    public PaymentCallbacks(PaymentNotifications notifications, ProviderStatusClient providerStatus,
            PlatformSecurityContext security, ObjectMapper mapper, OperatorActionLog actions,
            @Value("${nsure.integration.payment-callback-secret:local-payment-callback-secret}") String secret,
            @Value("${nsure.integration.callback-replay-window-seconds:300}") long windowSeconds,
            @Value("${nsure.integration.provider-simulator:true}") boolean simulator) {
        this.notifications = notifications;
        this.providerStatus = providerStatus;
        this.security = security;
        this.mapper = mapper;
        this.actions = actions;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.windowSeconds = windowSeconds;
        this.simulator = simulator;
    }

    /**
     * The provider's callback: {provider, providerTransactionId, policyRef, amount, currency, method, status}. Only a
     * settlement (status SETTLED) confirms money; anything unsigned, wrongly signed or outside the window changes
     * nothing (403).
     */
    public IntegrationViews.InboundView callback(String raw, String signature, String timestamp) {
        JsonNode body = verified(raw, signature, timestamp);
        String status = body.hasNonNull("status") ? body.get("status").asText().toUpperCase(Locale.ROOT) : "";
        if (!"SETTLED".equals(status)) {
            throw new IllegalArgumentException("Only settlement callbacks (status SETTLED) confirm a payment; "
                    + "this one says '" + status + "'.");
        }
        ObjectNode canonical = mapper.createObjectNode();
        canonical.put("provider", text(body, "provider"));
        canonical.put("providerReference", text(body, "providerTransactionId"));
        canonical.put("policyRef", text(body, "policyRef"));
        canonical.put("amount", text(body, "amount"));
        canonical.put("currency", text(body, "currency"));
        canonical.put("method", text(body, "method"));
        return notifications.receiveVerified(canonical);
    }

    private JsonNode verified(String raw, String signature, String timestamp) {
        long ts;
        try {
            ts = Long.parseLong(timestamp == null ? "" : timestamp.trim());
        } catch (NumberFormatException ex) {
            throw new NoAuthorizationException("The callback timestamp is missing or not a number.");
        }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > windowSeconds) {
            throw new NoAuthorizationException("The callback timestamp is outside the " + windowSeconds
                    + "-second replay window.");
        }
        String expected = "sha256=" + hmac(ts + "." + (raw == null ? "" : raw));
        if (signature == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            throw new NoAuthorizationException("The callback signature is missing or does not match.");
        }
        try {
            return mapper.readTree(raw);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("The callback body is not valid JSON.");
        }
    }

    /** The signature a provider computes over "timestamp.body". */
    String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Simulator only: {provider, providerTransactionId, policyRef, amount, currency?, method?, providerHolds,
     * repeat, tamper}. The simulated provider records the money (when it holds it), signs the callback and sends it
     * (twice with repeat; altered after signing with tamper) through the real verification. Returns each attempt.
     */
    public Map<String, Object> simulate(JsonNode request) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_INTEGRATION");
        if (!simulator) {
            throw new IllegalArgumentException("The provider simulator is off in this environment.");
        }
        String provider = required(request, "provider");
        String transaction = required(request, "providerTransactionId");
        String amount = required(request, "amount");
        String currency = text(request, "currency") == null ? "BWP" : text(request, "currency");
        if (request.path("providerHolds").asBoolean(true)) {
            providerStatus.simulateReceipt(provider, transaction, amount, currency);
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("provider", provider);
        body.put("providerTransactionId", transaction);
        body.put("policyRef", required(request, "policyRef"));
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("method", text(request, "method") == null ? "Bank EFT" : text(request, "method"));
        body.put("status", "SETTLED");
        String raw = body.toString();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = "sha256=" + hmac(timestamp + "." + raw);
        boolean tamper = request.path("tamper").asBoolean(false);
        int sends = request.path("repeat").asBoolean(false) ? 2 : 1;
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (int i = 0; i < sends; i++) {
            Map<String, Object> attempt = new LinkedHashMap<>();
            attempt.put("attempt", i + 1);
            String sent = tamper ? raw.replace("\"amount\":\"" + amount + "\"", "\"amount\":\"" + amount + "0\"") : raw;
            try {
                IntegrationViews.InboundView result = callback(sent, signature, timestamp);
                attempt.put("accepted", true);
                attempt.put("inboundId", result.id());
                attempt.put("status", result.status());
                attempt.put("detail", i == 0 ? result.detail()
                        : "Repeated callback acknowledged with the first result; no second effect.");
            } catch (NoAuthorizationException ex) {
                attempt.put("accepted", false);
                attempt.put("status", "REFUSED");
                attempt.put("detail", ex.getMessage());
            }
            attempts.add(attempt);
        }
        actions.record("SIMULATED_CALLBACK", "BANK_PAYMENTS", provider + ":" + transaction,
                (tamper ? "tampered " : "") + (sends > 1 ? "repeated " : "") + "signed callback",
                attempts.stream().map(a -> String.valueOf(a.get("status"))).toList().toString(), user.getUsername());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signature", signature);
        out.put("timestamp", timestamp);
        out.put("body", raw);
        out.put("attempts", attempts);
        return out;
    }

    /** NS-SH12-004 sweep: asks again about every confirmation whose outcome is unknown. Returns how many resolved. */
    public int requeryUnknown() {
        int resolved = 0;
        for (Long id : notifications.unknownIds()) {
            String status = notifications.requerySystem(id);
            if (status != null && !InboundMessage.UNKNOWN.equals(status)) {
                resolved++;
            }
        }
        return resolved;
    }

    @Scheduled(fixedDelayString = "${nsure.integration.unknown-requery-ms:300000}",
            initialDelayString = "${nsure.integration.unknown-requery-ms:300000}")
    public void scheduledRequery() {
        try {
            requeryUnknown();
        } catch (RuntimeException ex) {
            log.warn("Unknown-outcome re-query failed", ex);
        }
    }

    private static String required(JsonNode body, String field) {
        String value = text(body, field);
        if (value == null) {
            throw new com.nsure.common.exception.InvalidFieldException(field, field + " is required.");
        }
        return value;
    }

    private static String text(JsonNode body, String field) {
        return body != null && body.hasNonNull(field) && !body.get(field).asText().isBlank()
                ? body.get(field).asText().trim() : null;
    }
}
