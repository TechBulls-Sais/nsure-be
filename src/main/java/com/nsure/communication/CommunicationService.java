package com.nsure.communication;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.DomainEvent;
import com.nsure.portfolio.CustomerAccount;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Communications (SH09B): template versions with maker-checker approval, customer channel preferences, the message
 * log, and the event-driven notifications the {@link CommunicationEventHandler} asks for.
 */
@Service
public class CommunicationService {

    static final String EMAIL = "EMAIL";
    static final String WHATSAPP = "WHATSAPP";
    static final String CUSTOMER = "CUSTOMER";
    static final String INTERMEDIARY = "INTERMEDIARY";
    /** eventType on a message a staff member wrote by hand. */
    static final String SERVICE_MESSAGE = "ServiceMessage";

    private static final Set<String> CHANNELS = Set.of(EMAIL, WHATSAPP);
    private static final Set<String> AUDIENCES = Set.of(CUSTOMER, INTERMEDIARY);
    private static final Set<String> MESSAGE_STATES = Set.of(CommunicationMessage.QUEUED,
            CommunicationMessage.SUBMITTED, CommunicationMessage.DELIVERED, CommunicationMessage.FAILED,
            CommunicationMessage.SUPPRESSED);
    private static final Logger log = LoggerFactory.getLogger(CommunicationService.class);

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final CommunicationDispatcher dispatcher;
    private final String portalLink;

    public CommunicationService(EntityManager em, PlatformSecurityContext security,
            CommunicationDispatcher dispatcher,
            @Value("${nsure.communications.portal-link:http://127.0.0.1:5180}") String portalLink) {
        this.em = em;
        this.security = security;
        this.dispatcher = dispatcher;
        this.portalLink = portalLink;
    }

    // ── templates ────────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CommunicationViews.TemplateView> templates() {
        user("READ_COMMUNICATION");
        return em.createQuery("select t from MessageTemplate t order by t.code asc, t.version desc",
                        MessageTemplate.class)
                .getResultList().stream().map(CommunicationViews.TemplateView::of).toList();
    }

    /** Drafts a template; an existing code gets its next version (same event, channel and audience). */
    @Transactional
    public CommunicationViews.TemplateView createTemplate(JsonNode body) {
        AppUser user = user("MANAGE_COMMUNICATION");
        String code = upper(text(body, "code"));
        if (code == null || !code.matches("[A-Z0-9_]{3,40}")) {
            throw new IllegalArgumentException(
                    "Give the template a code of 3 to 40 letters, digits or underscores, e.g. POLICY_ISSUED_EMAIL.");
        }
        List<MessageTemplate> versions = em.createQuery(
                        "select t from MessageTemplate t where t.code = :code order by t.version desc",
                        MessageTemplate.class)
                .setParameter("code", code)
                .getResultList();
        MessageTemplate latest = versions.isEmpty() ? null : versions.get(0);
        String eventType = text(body, "eventType");
        String channel = upper(text(body, "channel"));
        String audience = upper(text(body, "audience"));
        if (latest != null) {
            eventType = same("event type", eventType, latest.getEventType(), code);
            channel = same("channel", channel, latest.getChannel(), code);
            audience = same("audience", audience, latest.getAudience(), code);
        }
        if (eventType == null || !MergeFields.eventTypes().contains(eventType)) {
            throw new IllegalArgumentException("Choose the event the template is for: "
                    + String.join(", ", MergeFields.eventTypes()) + ".");
        }
        if (channel == null || !CHANNELS.contains(channel)) {
            throw new IllegalArgumentException("Channel must be EMAIL or WHATSAPP.");
        }
        audience = audience == null ? CUSTOMER : audience;
        if (!AUDIENCES.contains(audience)) {
            throw new IllegalArgumentException("Audience must be CUSTOMER or INTERMEDIARY.");
        }
        String subject = text(body, "subject");
        String content = text(body, "body");
        validateContent(channel, subject, content);
        MessageTemplate template = new MessageTemplate(code, eventType, channel, audience,
                latest == null ? 1 : latest.getVersion() + 1, subject, content, user.getUsername());
        em.persist(template);
        return CommunicationViews.TemplateView.of(template);
    }

    /**
     * Checker step: another user approves, and the previously approved version of the code is retired. Super user and
     * Super admin may approve a version they drafted.
     */
    @Transactional
    public CommunicationViews.TemplateView approveTemplate(Long id) {
        AppUser user = user("APPROVE_TEMPLATE");
        MessageTemplate template = em.find(MessageTemplate.class, id);
        if (template == null) {
            throw new NotFoundException("Template " + id + " not found");
        }
        if (!MessageTemplate.DRAFT.equals(template.getStatus())) {
            throw new IllegalArgumentException("Only a draft can be approved; " + template.getCode() + " v"
                    + template.getVersion() + " is " + template.getStatus() + ".");
        }
        if (template.getCreatedBy().equalsIgnoreCase(user.getUsername()) && !user.isSuperAdmin()) {
            throw new NoAuthorizationException(
                    "You drafted this template version, so another user must approve it (maker-checker).");
        }
        List<String> unknown = MergeFields.unknown(template.getEventType(), template.getSubject(), template.getBody());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown merge field " + braces(unknown) + " for "
                    + template.getEventType() + ". Use only: " + braces(MergeFields.known(template.getEventType()))
                    + ".");
        }
        em.createQuery("select t from MessageTemplate t where t.code = :code and t.status = :approved",
                        MessageTemplate.class)
                .setParameter("code", template.getCode())
                .setParameter("approved", MessageTemplate.APPROVED)
                .getResultList()
                .forEach(MessageTemplate::retire);
        template.approve(user.getUsername());
        return CommunicationViews.TemplateView.of(template);
    }

    // ── messages ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CommunicationViews.MessageView> messages(String status) {
        user("READ_COMMUNICATION");
        String state = upper(status);
        if (state != null && !MESSAGE_STATES.contains(state)) {
            throw new IllegalArgumentException("Unknown message status " + status + ".");
        }
        var query = em.createQuery("select m from CommunicationMessage m"
                + (state == null ? "" : " where m.status = :status") + " order by m.id desc",
                CommunicationMessage.class);
        if (state != null) {
            query.setParameter("status", state);
        }
        return query.setMaxResults(500)
                .getResultList().stream().map(CommunicationViews.MessageView::of).toList();
    }

    /** A service message written by staff; consent and opt-out apply exactly as for event messages. */
    @Transactional
    public CommunicationViews.MessageView sendManual(JsonNode body) {
        AppUser user = user("MANAGE_COMMUNICATION");
        CustomerAccount customer = customerByRef(text(body, "customerRef"));
        String channel = upper(text(body, "channel"));
        channel = channel == null ? EMAIL : channel;
        if (!CHANNELS.contains(channel)) {
            throw new IllegalArgumentException("Channel must be EMAIL or WHATSAPP.");
        }
        String subject = text(body, "subject");
        String content = text(body, "body");
        validateContent(channel, subject, content);
        CommunicationMessage message = new CommunicationMessage("manual:" + UUID.randomUUID(), null,
                SERVICE_MESSAGE, customer.getRef(), customer.getRef(), customer.getName(), channel,
                user.getUsername());
        message.content(null, null, subject, content);
        route(message, customer, channel);
        save(message);
        return CommunicationViews.MessageView.of(message);
    }

    /** Puts a message from the failed queue back in the send queue with a fresh set of attempts. */
    @Transactional
    public CommunicationViews.MessageView retry(Long id) {
        user("MANAGE_COMMUNICATION");
        CommunicationMessage message = em.find(CommunicationMessage.class, id);
        if (message == null) {
            throw new NotFoundException("Message " + id + " not found");
        }
        if (!CommunicationMessage.FAILED.equals(message.getStatus())) {
            throw new IllegalArgumentException("Only a failed message can be retried; " + message.getRef() + " is "
                    + message.getStatus() + ".");
        }
        message.retry();
        return CommunicationViews.MessageView.of(message);
    }

    /** Sends every queued message now (the timer does the same in the background). */
    public CommunicationViews.DispatchView dispatchNow() {
        user("MANAGE_COMMUNICATION");
        return dispatcher.dispatch();
    }

    // ── preferences ──────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CommunicationViews.CustomerPreferencesView preferences(String customerRef) {
        user("READ_COMMUNICATION");
        return preferencesOf(customerByRef(customerRef));
    }

    @Transactional
    public CommunicationViews.CustomerPreferencesView setPreference(String customerRef, JsonNode body) {
        AppUser user = user("MANAGE_COMMUNICATION");
        CustomerAccount customer = customerByRef(customerRef);
        String channel = upper(text(body, "channel"));
        if (channel == null || !CHANNELS.contains(channel)) {
            throw new IllegalArgumentException("Channel must be EMAIL or WHATSAPP.");
        }
        String status = upper(text(body, "status"));
        if (!ChannelPreference.OPTED_IN.equals(status) && !ChannelPreference.OPTED_OUT.equals(status)) {
            throw new IllegalArgumentException("Status must be OPTED_IN or OPTED_OUT.");
        }
        ChannelPreference preference = preference(customer.getRef(), channel);
        String contact = text(body, "contact");
        if (contact == null && preference != null) {
            contact = preference.getContact();
        }
        if (contact == null && EMAIL.equals(channel)) {
            contact = blankToNull(customer.getEmail());
        }
        if (contact != null && contact.length() > 150) {
            throw new IllegalArgumentException("Keep the contact to 150 characters.");
        }
        if (contact != null && EMAIL.equals(channel) && !contact.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        if (contact != null && WHATSAPP.equals(channel) && digits(contact).length() < 7) {
            throw new IllegalArgumentException("Enter a valid WhatsApp mobile number.");
        }
        if (ChannelPreference.OPTED_IN.equals(status) && contact == null) {
            throw new IllegalArgumentException("Give the " + label(channel) + " contact to opt the customer in.");
        }
        boolean verified = contact != null && (EMAIL.equals(channel)
                ? contact.equalsIgnoreCase(String.valueOf(customer.getEmail()))
                : digits(contact).equals(digits(customer.getPhone())));
        if (preference == null) {
            preference = new ChannelPreference(customer.getRef(), channel);
            preference.set(status, contact, verified, user.getUsername());
            em.persist(preference);
        } else {
            preference.set(status, contact, verified, user.getUsername());
        }
        return preferencesOf(customer);
    }

    // ── event notifications ──────────────────────────────────────────────────────────────────────────

    /**
     * One message per approved customer template of the event type. Idempotent: the dedup key (event + recipient +
     * template version) already present means the event was handled. A recipient who cannot be messaged gets a
     * SUPPRESSED message with the reason; nothing is dropped silently.
     */
    @Transactional
    public int notifyFor(DomainEvent event) {
        List<MessageTemplate> templates = em.createQuery(
                        "select t from MessageTemplate t where t.eventType = :type and t.status = :approved"
                                + " and t.audience = :audience order by t.code asc", MessageTemplate.class)
                .setParameter("type", event.eventType())
                .setParameter("approved", MessageTemplate.APPROVED)
                .setParameter("audience", CUSTOMER)
                .getResultList();
        if (templates.isEmpty()) {
            return 0;
        }
        String name = event.text("customerName");
        CustomerAccount customer = null;
        String unresolved = null;
        String ref = event.text("customerRef");
        if (ref != null) {
            customer = em.createQuery("select c from CustomerAccount c where c.ref = :ref", CustomerAccount.class)
                    .setParameter("ref", ref).getResultStream().findFirst().orElse(null);
        }
        if (customer == null) {
            List<CustomerAccount> named = name == null ? List.of() : em.createQuery(
                            "select c from CustomerAccount c where c.name = :name order by c.id asc",
                            CustomerAccount.class)
                    .setParameter("name", name).getResultList();
            if (named.size() == 1) {
                customer = named.get(0);
            } else if (named.isEmpty()) {
                unresolved = "No customer record matches " + (name == null ? "the event" : name) + ".";
            } else {
                unresolved = named.size() + " customers are named " + name
                        + "; the recipient is ambiguous, so nothing was sent.";
            }
        }
        int created = 0;
        for (MessageTemplate template : templates) {
            String recipientKey = customer == null ? "UNRESOLVED" : customer.getRef();
            String dedupKey = event.eventId() + ":" + recipientKey + ":" + template.getCode() + ":v"
                    + template.getVersion();
            Long existing = em.createQuery(
                            "select count(m) from CommunicationMessage m where m.dedupKey = :key", Long.class)
                    .setParameter("key", dedupKey).getSingleResult();
            if (existing > 0) {
                continue;
            }
            CommunicationMessage message = new CommunicationMessage(dedupKey, event.eventId(), event.eventType(),
                    event.aggregateRef(), customer == null ? null : customer.getRef(),
                    customer == null ? name : customer.getName(), template.getChannel(), "system");
            String customerRef = customer == null ? "" : customer.getRef();
            java.util.function.Function<String, String> values = field -> switch (field) {
                case MergeFields.CUSTOMER_REF -> customerRef;
                case MergeFields.PORTAL_LINK -> portalLink;
                default -> event.text(field);
            };
            message.content(template.getCode(), template.getVersion(),
                    MergeFields.render(template.getSubject(), values), MergeFields.render(template.getBody(), values));
            if (customer == null) {
                message.suppress(null, unresolved);
            } else {
                route(message, customer, template.getChannel());
            }
            save(message);
            if (CommunicationMessage.SUPPRESSED.equals(message.getStatus())) {
                log.info("Message {} for event {} suppressed: {}", message.getRef(), event.eventId(),
                        message.getLastError());
            }
            created++;
        }
        return created;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    /** Queues the message at the customer's contact for the channel, or suppresses it with the reason. */
    private void route(CommunicationMessage message, CustomerAccount customer, String channel) {
        Route route = route(customer, channel);
        if (route.reason() == null) {
            message.queue(route.contact());
        } else {
            message.suppress(route.contact(), route.reason());
        }
    }

    private record Route(String contact, String reason) {
    }

    private Route route(CustomerAccount customer, String channel) {
        ChannelPreference preference = preference(customer.getRef(), channel);
        String chosen = preference == null ? null : blankToNull(preference.getContact());
        if (EMAIL.equals(channel)) {
            String contact = chosen != null ? chosen : blankToNull(customer.getEmail());
            if (preference != null && ChannelPreference.OPTED_OUT.equals(preference.getStatus())) {
                return new Route(contact, "Customer opted out of Email messages.");
            }
            if (contact == null) {
                return new Route(null, "No email address on file.");
            }
            return new Route(contact, null);
        }
        if (preference == null) {
            return new Route(null, "Customer has not opted in to WhatsApp messages.");
        }
        if (ChannelPreference.OPTED_OUT.equals(preference.getStatus())) {
            return new Route(chosen, "Customer opted out of WhatsApp messages.");
        }
        if (chosen == null) {
            return new Route(null, "No WhatsApp number on file.");
        }
        return new Route(chosen, null);
    }

    private CommunicationViews.CustomerPreferencesView preferencesOf(CustomerAccount customer) {
        List<CommunicationViews.PreferenceView> channels = new ArrayList<>();
        for (String channel : List.of(EMAIL, WHATSAPP)) {
            ChannelPreference preference = preference(customer.getRef(), channel);
            Route route = route(customer, channel);
            String status = preference != null ? preference.getStatus()
                    : EMAIL.equals(channel) ? ChannelPreference.OPTED_IN : "NOT_SET";
            boolean verified = preference != null ? "Y".equals(preference.getVerified())
                    : EMAIL.equals(channel) && blankToNull(customer.getEmail()) != null;
            channels.add(new CommunicationViews.PreferenceView(channel, status, preference != null,
                    CommunicationViews.mask(route.contact()), verified ? "Y" : "N", route.reason() == null,
                    route.reason() == null ? "" : route.reason(),
                    preference == null ? "" : preference.getUpdatedBy(),
                    preference == null ? "" : CommunicationViews.time(preference.getUpdatedAt())));
        }
        return new CommunicationViews.CustomerPreferencesView(customer.getRef(), customer.getName(), channels);
    }

    private ChannelPreference preference(String customerRef, String channel) {
        return em.createQuery("select p from ChannelPreference p where p.customerRef = :ref and p.channel = :channel",
                        ChannelPreference.class)
                .setParameter("ref", customerRef)
                .setParameter("channel", channel)
                .getResultStream().findFirst().orElse(null);
    }

    private CustomerAccount customerByRef(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("Give the customer reference, e.g. CUS-1001.");
        }
        return em.createQuery("select c from CustomerAccount c where c.ref = :ref", CustomerAccount.class)
                .setParameter("ref", ref.trim().toUpperCase(Locale.ROOT))
                .getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Customer " + ref + " not found"));
    }

    private void save(CommunicationMessage message) {
        em.persist(message);
        em.flush();
        message.numbered();
    }

    private static void validateContent(String channel, String subject, String content) {
        if (content == null) {
            throw new IllegalArgumentException("Write the message body.");
        }
        if (content.length() > 2000) {
            throw new IllegalArgumentException("Keep the message body to 2000 characters.");
        }
        if (EMAIL.equals(channel) && subject == null) {
            throw new IllegalArgumentException("An email needs a subject.");
        }
        if (subject != null && subject.length() > 200) {
            throw new IllegalArgumentException("Keep the subject to 200 characters.");
        }
    }

    private static String same(String what, String given, String existing, String code) {
        if (given != null && !given.equals(existing)) {
            throw new IllegalArgumentException("Every version of " + code + " has the " + what + " " + existing
                    + ". Use a new code for a different " + what + ".");
        }
        return existing;
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    private static String braces(List<String> fields) {
        return String.join(", ", fields.stream().map(f -> "{{" + f + "}}").toList());
    }

    private static String label(String channel) {
        return EMAIL.equals(channel) ? "Email" : "WhatsApp";
    }

    static String digits(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    static String text(JsonNode body, String field) {
        if (body == null) {
            return null;
        }
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}
