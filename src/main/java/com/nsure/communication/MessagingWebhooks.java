package com.nsure.communication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.ConflictException;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.common.exception.NotFoundException;
import com.nsure.portfolio.CustomerAccount;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import com.nsure.servicedesk.ServiceDeskService;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Email/WhatsApp provider's callbacks (Solution Blueprint §5 and §6):
 *
 * <ul>
 *   <li>every callback is authenticated by an HMAC-SHA256 signature of its body with this insurer's webhook secret
 *       ({@code X-Nsure-Signature: sha256=<hex>}); an unsigned or wrongly signed callback changes nothing;</li>
 *   <li><b>status</b> callbacks move a message forward to delivered, read or failed; repeats and late arrivals are
 *       harmless;</li>
 *   <li><b>inbound</b> messages are stored once per provider message id. A sender matching exactly one customer is
 *       linked to that customer's service case (the open case it names, or a new one); an unknown or ambiguous sender
 *       goes to a triage queue where a person assigns it.</li>
 * </ul>
 */
@Service
public class MessagingWebhooks {

    private final EntityManager em;
    private final CommunicationService communications;
    private final ServiceDeskService serviceDesk;
    private final PlatformSecurityContext security;
    private final ObjectMapper mapper;
    private final Outbox outbox;
    private final EvidenceChain audit;
    private final byte[] secret;

    public MessagingWebhooks(EntityManager em, CommunicationService communications, ServiceDeskService serviceDesk,
            PlatformSecurityContext security, ObjectMapper mapper, Outbox outbox, EvidenceChain audit,
            @Value("${nsure.messaging.webhook-secret:local-messaging-webhook-secret}") String secret) {
        this.outbox = outbox;
        this.audit = audit;
        this.em = em;
        this.communications = communications;
        this.serviceDesk = serviceDesk;
        this.security = security;
        this.mapper = mapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** {providerMessageId, status: DELIVERED | READ | FAILED, detail}. */
    @Transactional
    public CommunicationViews.CallbackResult status(String raw, String signature) {
        JsonNode body = verified(raw, signature);
        String id = required(body, "providerMessageId");
        String status = communications.providerStatus(id, required(body, "status"), text(body, "detail"), raw);
        return new CommunicationViews.CallbackResult(status == null ? "IGNORED" : status, null);
    }

    /** {providerMessageId, channel: EMAIL | WHATSAPP, from, text, receivedAt (optional ISO time)}. */
    @Transactional
    public CommunicationViews.CallbackResult inbound(String raw, String signature) {
        JsonNode body = verified(raw, signature);
        String id = required(body, "providerMessageId");
        InboundCorrespondence earlier = em.createQuery(
                        "select i from InboundCorrespondence i where i.providerMessageId = :id", InboundCorrespondence.class)
                .setParameter("id", id).getResultStream().findFirst().orElse(null);
        if (earlier != null) {
            return new CommunicationViews.CallbackResult(earlier.getStatus(), earlier.getCaseRef());
        }
        String channel = required(body, "channel").toUpperCase(Locale.ROOT);
        if (!CommunicationService.EMAIL.equals(channel) && !CommunicationService.WHATSAPP.equals(channel)) {
            throw new IllegalArgumentException("channel must be EMAIL or WHATSAPP.");
        }
        LocalDateTime received = LocalDateTime.now();
        if (body.hasNonNull("receivedAt")) {
            try {
                received = LocalDateTime.parse(body.get("receivedAt").asText());
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("receivedAt must be an ISO date-time.");
            }
        }
        InboundCorrespondence message = new InboundCorrespondence(id, channel, required(body, "from"),
                required(body, "text"), received);
        List<CustomerAccount> matches = communications.customersWithContact(channel, message.getSender());
        if (matches.size() == 1) {
            CustomerAccount customer = matches.get(0);
            String caseRef = serviceDesk.linkCorrespondence(customer.getRef(), channel, message.getBody(), "system");
            message.linked(customer.getRef(), caseRef, "Matched by sender", "system");
        } else {
            message.triage(matches.isEmpty() ? "No customer has this " + label(channel) + " on file."
                    : matches.size() + " customers share this " + label(channel) + "; a person must choose.");
        }
        em.persist(message);
        em.flush();
        // SH09B: references only, never the sender or the text (NS-API-003).
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("inboundId", message.getId());
        data.put("channel", channel);
        data.put("status", message.getStatus());
        data.put("customerRef", message.getCustomerRef());
        data.put("caseRef", message.getCaseRef());
        outbox.publish(Events.INBOUND_MESSAGE_RECEIVED, "IN-" + message.getId(), data, "provider");
        return new CommunicationViews.CallbackResult(message.getStatus(), message.getCaseRef());
    }

    // ── staff triage ─────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CommunicationViews.InboundView> list(String status) {
        security.authenticatedUser().validateHasPermissionTo("READ_COMMUNICATION");
        String filter = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        jakarta.persistence.TypedQuery<InboundCorrespondence> query = em.createQuery("select i from InboundCorrespondence i"
                + (filter == null ? "" : " where i.status = :s") + " order by i.receivedAt desc, i.id desc",
                InboundCorrespondence.class);
        if (filter != null) {
            query.setParameter("s", filter);
        }
        return query.setMaxResults(200).getResultStream().map(CommunicationViews.InboundView::of).toList();
    }

    /** A person decides who an ambiguous message is from; it is then linked to that customer's case. */
    @Transactional
    public CommunicationViews.InboundView assign(Long id, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_COMMUNICATION");
        InboundCorrespondence message = em.find(InboundCorrespondence.class, id);
        if (message == null) {
            throw new NotFoundException("Inbound message " + id + " not found");
        }
        if (!InboundCorrespondence.TRIAGE.equals(message.getStatus())) {
            throw new ConflictException("This message is already linked to " + message.getCaseRef() + ".");
        }
        String customerRef = required(body, "customerRef").toUpperCase(Locale.ROOT);
        String caseRef = serviceDesk.linkCorrespondence(customerRef, message.getChannel(), message.getBody(),
                user.getUsername());
        message.linked(customerRef, caseRef, "Assigned in triage", user.getUsername());
        return CommunicationViews.InboundView.of(message);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private JsonNode verified(String raw, String signature) {
        String expected = "sha256=" + hmac(raw == null ? "" : raw);
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

    /** The signature a provider computes: hex HMAC-SHA256 of the raw body with the shared secret. */
    String hmac(String raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String label(String channel) {
        return CommunicationService.EMAIL.equals(channel) ? "email address" : "WhatsApp number";
    }

    private static String required(JsonNode body, String field) {
        String value = text(body, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value;
    }

    private static String text(JsonNode body, String field) {
        return body == null || !body.hasNonNull(field) || body.get(field).asText().isBlank() ? null
                : body.get(field).asText().trim();
    }
}
