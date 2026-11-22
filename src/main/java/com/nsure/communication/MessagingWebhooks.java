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
            @Value("${nsure.messaging.webhook-secret:}") String secret,
            org.springframework.core.env.Environment environment) {
        this.outbox = outbox;
        this.audit = audit;
        this.em = em;
        this.communications = communications;
        this.serviceDesk = serviceDesk;
        this.security = security;
        this.mapper = mapper;
        // NS-SH11-001/004: the published local secret is never shared with a real installation. It is used only in a
        // dev or demo profile when none is configured; anywhere else, without a configured secret every callback is
        // refused.
        String chosen = secret == null || secret.isBlank()
                ? environment.acceptsProfiles(org.springframework.core.env.Profiles.of("(dev | demo) & !prod"))
                        ? LOCAL_SECRET : null
                : secret.trim();
        this.secret = chosen == null ? null : chosen.getBytes(StandardCharsets.UTF_8);
    }

    /** The published secret for local and demo installations only. */
    static final String LOCAL_SECRET = "local-messaging-webhook-secret";

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
            message.matched(customer.getRef(), caseRef, "system");
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

    /** How a person may verify who sent an unverified reply before linking it (AT-SH09B-03). */
    static final List<String> VERIFICATION_METHODS = List.of("Called back on the registered number",
            "Replied to the registered email address and got a confirmation", "Security questions answered",
            "Identity document checked in person");

    /**
     * Completes the verification task (AT-SH09B-03): {customerRef, method, note}. The person records how they verified
     * that the customer sent the message; only then is it linked to that customer's case. Written to the evidence chain.
     */
    @Transactional
    public CommunicationViews.InboundView assign(Long id, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_COMMUNICATION");
        InboundCorrespondence message = triageTask(id);
        String customerRef = required(body, "customerRef").toUpperCase(Locale.ROOT);
        String method = text(body, "method");
        if (method == null || !VERIFICATION_METHODS.contains(method)) {
            throw new IllegalArgumentException("Record how you verified the sender: " + String.join("; ",
                    VERIFICATION_METHODS) + ".");
        }
        String note = text(body, "note");
        if (note != null && note.length() > 500) {
            throw new IllegalArgumentException("Keep the note to 500 characters.");
        }
        String caseRef = serviceDesk.linkCorrespondence(customerRef, message.getChannel(), message.getBody(),
                user.getUsername());
        message.verified(customerRef, caseRef, method, note, user.getUsername());
        audit.record(user.getUsername(), "Inbound sender verified", "Customer", customerRef,
                "Inbound " + message.getId() + " by " + message.getChannel() + ": " + method
                        + (note == null ? "" : " · " + note));
        return CommunicationViews.InboundView.of(message);
    }

    /** {release?}: takes the verification task, or gives it back. */
    @Transactional
    public CommunicationViews.InboundView own(Long id, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_COMMUNICATION");
        InboundCorrespondence message = triageTask(id);
        boolean release = body != null && body.path("release").asBoolean(false);
        if (!release && message.getTaskOwner() != null && !message.getTaskOwner().equalsIgnoreCase(user.getUsername())) {
            throw new ConflictException("This verification task is already being worked by " + message.getTaskOwner()
                    + ".");
        }
        message.own(release ? null : user.getUsername());
        return CommunicationViews.InboundView.of(message);
    }

    private InboundCorrespondence triageTask(Long id) {
        InboundCorrespondence message = em.find(InboundCorrespondence.class, id);
        if (message == null) {
            throw new NotFoundException("Inbound message " + id + " not found");
        }
        if (!InboundCorrespondence.TRIAGE.equals(message.getStatus())) {
            throw new ConflictException("This message is already linked to " + message.getCaseRef() + ".");
        }
        return message;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private JsonNode verified(String raw, String signature) {
        if (secret == null) {
            throw new NoAuthorizationException("Messaging callbacks are not configured on this installation"
                    + " (nsure.messaging.webhook-secret).");
        }
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
