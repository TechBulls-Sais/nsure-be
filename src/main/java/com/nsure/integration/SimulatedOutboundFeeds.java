package com.nsure.integration;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Outbound interfaces that consume outbox events (NS-SH12-002 webhooks, SH07 bordereau transmission). No real partner
 * endpoint exists, so each feed is a <b>simulated transmission</b>: it signs the event envelope (HMAC-SHA256, references
 * only) and records what would have been sent with the simulated acknowledgement, once per event. When the interface
 * is switched off in the catalogue, the dispatcher holds the deliveries back as transient failures, so the backlog,
 * retries, dead letters and recovery can all be seen in the integration monitor. No outbound HTTP call is made.
 */
@Configuration
public class SimulatedOutboundFeeds {

    static final String PARTNER_WEBHOOKS = "partner.webhooks";
    static final String REINSURER_FEED = "reinsurer.bordereau-feed";

    @Bean
    DomainEventHandler partnerWebhookFeed(JdbcTemplate jdbc,
            @Value("${nsure.integration.webhook-signing-secret:local-partner-webhook-secret}") String secret) {
        return new Feed(jdbc, secret, PARTNER_WEBHOOKS, "PARTNER_WEBHOOKS", Set.of(Events.POLICY_ISSUED,
                Events.COVER_LAPSED, Events.POLICY_REINSTATED, Events.POLICY_TERMINATED, Events.MEMBER_COVER_AUTHORISED,
                Events.CLAIM_PAYMENT_SETTLED));
    }

    @Bean
    DomainEventHandler reinsurerBordereauFeed(JdbcTemplate jdbc,
            @Value("${nsure.integration.webhook-signing-secret:local-partner-webhook-secret}") String secret) {
        return new Feed(jdbc, secret, REINSURER_FEED, "REINSURER", Set.of(Events.BORDEREAU_SUBMITTED));
    }

    /** One simulated outbound transmission per event, idempotent on the event id. */
    static final class Feed implements DomainEventHandler {

        private final JdbcTemplate jdbc;
        private final byte[] secret;
        private final String name;
        private final String interfaceCode;
        private final Set<String> types;

        Feed(JdbcTemplate jdbc, String secret, String name, String interfaceCode, Set<String> types) {
            this.jdbc = jdbc;
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
            this.name = name;
            this.interfaceCode = interfaceCode;
            this.types = types;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Set<String> eventTypes() {
            return types;
        }

        @Override
        public void handle(DomainEvent event) {
            Integer done = jdbc.queryForObject("select count(*) from int_outbound_message where handler_name = ?"
                    + " and event_id = ?", Integer.class, name, event.eventId());
            if (done != null && done > 0) {
                return;
            }
            String envelope = "{\"eventId\":\"" + event.eventId() + "\",\"eventType\":\"" + event.eventType()
                    + "\",\"aggregateRef\":\"" + event.aggregateRef() + "\",\"occurredAt\":\"" + event.occurredAt()
                    + "\",\"data\":" + event.data() + "}";
            jdbc.update("insert into int_outbound_message (interface_code, handler_name, event_id, event_type,"
                    + " aggregate_ref, payload_hash, signature, ack_ref, transmitted_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    interfaceCode, name, event.eventId(), event.eventType(), event.aggregateRef(), sha256(envelope),
                    "sha256=" + hmac(envelope), "ACK-" + event.eventId().substring(0, 8).toUpperCase(Locale.ROOT),
                    LocalDateTime.now());
        }

        private String hmac(String body) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret, "HmacSHA256"));
                return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException(ex);
            }
        }

        private static String sha256(String body) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(body.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}
