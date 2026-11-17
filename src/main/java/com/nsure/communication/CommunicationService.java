package com.nsure.communication;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.DomainEvent;
import com.nsure.integration.Events;
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
            CommunicationMessage.SUBMITTED, CommunicationMessage.ACCEPTED, CommunicationMessage.DELIVERED,
            CommunicationMessage.FAILED, CommunicationMessage.SUPPRESSED, CommunicationMessage.READ);
    private static final Logger log = LoggerFactory.getLogger(CommunicationService.class);

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final CommunicationDispatcher dispatcher;
    private final EvidenceChain audit;
    private final MessageTrail trail;
    private final String portalLink;

    public CommunicationService(EntityManager em, PlatformSecurityContext security,
            CommunicationDispatcher dispatcher, EvidenceChain audit, MessageTrail trail,
            @Value("${nsure.communications.portal-link:http://127.0.0.1:5180}") String portalLink) {
        this.em = em;
        this.security = security;
        this.dispatcher = dispatcher;
        this.audit = audit;
        this.trail = trail;
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
        String purpose = upper(text(body, "purpose"));
        if (purpose != null && MessagePurposes.PROMOTIONAL.contains(purpose)) {
            // NS-SH09B-007: campaigns need their own permission, consent basis and approval, which this release lacks.
            throw new IllegalArgumentException("Templates are for service notices about a policy, claim or case. "
                    + "Promotional campaigns need a separate permission, marketing consent and approval, and are not "
                    + "available.");
        }
        String subject = text(body, "subject");
        String content = text(body, "body");
        validateContent(channel, subject, content);
        MessageTemplate template = new MessageTemplate(code, eventType, channel, audience,
                latest == null ? 1 : latest.getVersion() + 1, subject, content, user.getUsername());
        em.persist(template);
        audit.record(user.getUsername(), "Template drafted", "Message template",
                template.getCode() + " v" + template.getVersion(), template.getEventType() + " · " + template.getChannel()
                        + " · " + template.getAudience());
        return CommunicationViews.TemplateView.of(template);
    }

    /**
     * The template designer's preview: {eventType, audience, subject, body} rendered with sample values, with the
     * merge fields the audience may use and any it may not. Nothing is saved.
     */
    @Transactional(readOnly = true)
    public CommunicationViews.TemplatePreview preview(JsonNode body) {
        AppUser user = security.authenticatedUser();
        if (!user.hasPermissionTo("MANAGE_COMMUNICATION") && !user.hasPermissionTo("APPROVE_TEMPLATE")) {
            throw new NoAuthorizationException("User has no authority to design message templates");
        }
        String eventType = text(body, "eventType");
        if (eventType == null || !MergeFields.eventTypes().contains(eventType)) {
            throw new IllegalArgumentException("Choose the event the template is for: "
                    + String.join(", ", MergeFields.eventTypes()) + ".");
        }
        String audience = upper(text(body, "audience"));
        audience = audience == null ? CUSTOMER : audience;
        String subject = text(body, "subject");
        String content = text(body, "body");
        return new CommunicationViews.TemplatePreview(MergeFields.render(subject, MergeFields::sample),
                MergeFields.render(content, MergeFields::sample), MergeFields.placeholders(subject, content),
                MergeFields.known(eventType, audience), MergeFields.unknownFor(eventType, audience, subject, content),
                MessagePurposes.of(eventType));
    }

    /**
     * Checker step: another user approves, and the previously approved version of the code is retired. Nobody
     * approves a version they drafted, the administrator included (Nsure Life NS-UX-004).
     */
    @Transactional
    public CommunicationViews.TemplateView approveTemplate(Long id) {
        AppUser user = user("APPROVE_TEMPLATE");
        MessageTemplate template = template(id);
        if (!MessageTemplate.DRAFT.equals(template.getStatus())) {
            throw new IllegalArgumentException("Only a draft can be approved; " + template.getCode() + " v"
                    + template.getVersion() + " is " + template.getStatus() + ".");
        }
        if (template.getCreatedBy().equalsIgnoreCase(user.getUsername())) {
            throw new NoAuthorizationException(
                    "You drafted this template version, so another user must approve it (maker-checker).");
        }
        List<String> unknown = MergeFields.unknownFor(template.getEventType(), template.getAudience(),
                template.getSubject(), template.getBody());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown merge field " + braces(unknown) + " for "
                    + template.getEventType() + (INTERMEDIARY.equals(template.getAudience()) ? " to an intermediary" : "")
                    + ". Use only: " + braces(MergeFields.known(template.getEventType(), template.getAudience())) + ".");
        }
        em.createQuery("select t from MessageTemplate t where t.code = :code and t.status = :approved",
                        MessageTemplate.class)
                .setParameter("code", template.getCode())
                .setParameter("approved", MessageTemplate.APPROVED)
                .getResultList()
                .forEach(MessageTemplate::retire);
        template.approve(user.getUsername());
        audit.record(user.getUsername(), "Template approved", "Message template",
                template.getCode() + " v" + template.getVersion(), "Drafted by " + template.getCreatedBy());
        return CommunicationViews.TemplateView.of(template);
    }

    /**
     * D-22, first step of withdrawing an approved version without a replacement: {reason}. The version keeps being used
     * until another holder of APPROVE_TEMPLATE confirms, as with any approval of template content.
     */
    @Transactional
    public CommunicationViews.TemplateView requestWithdrawal(Long id, JsonNode body) {
        AppUser user = security.authenticatedUser();
        if (!user.hasPermissionTo("MANAGE_COMMUNICATION") && !user.hasPermissionTo("APPROVE_TEMPLATE")) {
            throw new NoAuthorizationException("User has no authority to withdraw message templates");
        }
        MessageTemplate template = template(id);
        if (!MessageTemplate.APPROVED.equals(template.getStatus())) {
            throw new IllegalArgumentException("Only an approved template can be withdrawn; " + template.getCode() + " v"
                    + template.getVersion() + " is " + template.getStatus() + ".");
        }
        if (template.isWithdrawalPending()) {
            throw new ConflictException(template.getCode() + " v" + template.getVersion()
                    + " already has a withdrawal waiting for approval, asked by " + template.getWithdrawalRequestedBy()
                    + ".");
        }
        String reason = text(body, "reason");
        if (reason == null || reason.length() < 10 || reason.length() > 500) {
            throw new IllegalArgumentException("Give the reason for withdrawing the template (10 to 500 characters).");
        }
        template.requestWithdrawal(user.getUsername(), reason);
        audit.record(user.getUsername(), "Template withdrawal requested", "Message template",
                template.getCode() + " v" + template.getVersion(), reason);
        return CommunicationViews.TemplateView.of(template);
    }

    /** D-22, second step: someone other than the requester confirms; no new message uses the version from now on. */
    @Transactional
    public CommunicationViews.TemplateView approveWithdrawal(Long id) {
        AppUser user = user("APPROVE_TEMPLATE");
        MessageTemplate template = pendingWithdrawal(id);
        if (template.getWithdrawalRequestedBy().equalsIgnoreCase(user.getUsername())) {
            throw new NoAuthorizationException(
                    "You asked for this withdrawal, so another user must approve it (maker-checker).");
        }
        template.withdraw(user.getUsername());
        audit.record(user.getUsername(), "Template withdrawn", "Message template",
                template.getCode() + " v" + template.getVersion(),
                "Asked by " + template.getWithdrawalRequestedBy() + ": " + template.getWithdrawalReason());
        return CommunicationViews.TemplateView.of(template);
    }

    /** D-22: the withdrawal is declined and the version stays approved. */
    @Transactional
    public CommunicationViews.TemplateView declineWithdrawal(Long id) {
        AppUser user = user("APPROVE_TEMPLATE");
        MessageTemplate template = pendingWithdrawal(id);
        String asked = template.getWithdrawalRequestedBy() + ": " + template.getWithdrawalReason();
        template.declineWithdrawal();
        audit.record(user.getUsername(), "Template withdrawal declined", "Message template",
                template.getCode() + " v" + template.getVersion(), "Asked by " + asked);
        return CommunicationViews.TemplateView.of(template);
    }

    private MessageTemplate pendingWithdrawal(Long id) {
        MessageTemplate template = template(id);
        if (!template.isWithdrawalPending()) {
            throw new ConflictException(template.getCode() + " v" + template.getVersion()
                    + " has no withdrawal waiting for approval.");
        }
        return template;
    }

    private MessageTemplate template(Long id) {
        MessageTemplate template = em.find(MessageTemplate.class, id);
        if (template == null) {
            throw new NotFoundException("Template " + id + " not found");
        }
        return template;
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

    /** One message with its trail: every state, the provider's reports and the staff actions (NS-SH09B-007). */
    @Transactional(readOnly = true)
    public CommunicationViews.MessageDetailView message(Long id) {
        user("READ_COMMUNICATION");
        CommunicationMessage message = messageById(id);
        return new CommunicationViews.MessageDetailView(CommunicationViews.MessageView.of(message),
                trail.of(message.getId()));
    }

    /** {release?}: takes a failed message to work it, or gives it back to the queue (SH09B failed-message queue). */
    @Transactional
    public CommunicationViews.MessageView own(Long id, JsonNode body) {
        AppUser user = user("MANAGE_COMMUNICATION");
        CommunicationMessage message = messageById(id);
        boolean release = body != null && body.path("release").asBoolean(false);
        if (release) {
            if (message.getOwner() == null) {
                throw new ConflictException(message.getRef() + " has no owner.");
            }
            String previous = message.getOwner();
            message.own(null);
            trail.noted(message, "Given back to the queue (was " + previous + ")", user.getUsername());
        } else {
            if (!CommunicationMessage.FAILED.equals(message.getStatus())) {
                throw new IllegalArgumentException("Only a message in the failed queue is taken; " + message.getRef()
                        + " is " + message.getStatus() + ".");
            }
            if (message.getOwner() != null && !message.getOwner().equalsIgnoreCase(user.getUsername())) {
                throw new ConflictException(message.getRef() + " is already being worked by " + message.getOwner() + ".");
            }
            message.own(user.getUsername());
            trail.noted(message, "Taken by " + user.getUsername(), user.getUsername());
        }
        return CommunicationViews.MessageView.of(message);
    }

    private CommunicationMessage messageById(Long id) {
        CommunicationMessage message = em.find(CommunicationMessage.class, id);
        if (message == null) {
            throw new NotFoundException("Message " + id + " not found");
        }
        return message;
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
        // Consent first (AT-SH09B-02): an opted-out or not-opted-in channel is recorded as SUPPRESSED with the reason,
        // whatever else would have stopped the send, so the opt-out is visible in the log (D-23).
        Route route = route(customer, channel);
        if (route.reason() == null && WHATSAPP.equals(channel)) {
            // Blueprint §5 / WhatsApp Business policy: a free-form message only within 24 hours of the customer's last
            // WhatsApp message; outside that window only approved templates are sent.
            java.time.LocalDateTime last = lastInbound(customer.getRef(), WHATSAPP);
            if (last == null || last.isBefore(java.time.LocalDateTime.now().minusHours(24))) {
                throw new IllegalArgumentException("A free-form WhatsApp message can only be sent within 24 hours of "
                        + customer.getName() + "'s last WhatsApp message. Use Email, or an approved template.");
            }
        }
        // SH09B: a reply on a service case joins that case's conversation.
        String caseRef = upper(text(body, "caseRef"));
        if (caseRef != null) {
            String owner = em.createQuery("select c.customerRef from ServiceCase c where c.ref = :ref", String.class)
                    .setParameter("ref", caseRef).getResultStream().findFirst().orElse(null);
            if (owner == null || !owner.equals(customer.getRef())) {
                throw new IllegalArgumentException("Service case " + caseRef + " is not " + customer.getName() + "'s.");
            }
        }
        CommunicationMessage message = new CommunicationMessage("manual:" + UUID.randomUUID(), null,
                SERVICE_MESSAGE, caseRef != null ? caseRef : customer.getRef(), customer.getRef(), customer.getName(),
                channel, user.getUsername());
        message.content(null, null, subject, content);
        message.recipient(CUSTOMER, "Customer " + customer.getRef() + " chosen by " + user.getUsername()
                + " for a service message" + (caseRef == null ? "" : " on case " + caseRef),
                MessagePurposes.of(SERVICE_MESSAGE));
        if (route.reason() == null) {
            message.queue(route.contact());
        } else {
            message.suppress(route.contact(), route.reason());
        }
        save(message);
        trail.requested(message, user.getUsername(), null);
        audit.record(user.getUsername(), "Service message written", "Message", message.getRef(),
                label(channel) + " to " + customer.getRef() + " · " + message.getStatus());
        return CommunicationViews.MessageView.of(message);
    }

    /** Puts a message from the failed queue back in the send queue with a fresh set of attempts. */
    @Transactional
    public CommunicationViews.MessageView retry(Long id) {
        AppUser user = user("MANAGE_COMMUNICATION");
        CommunicationMessage message = messageById(id);
        if (!CommunicationMessage.FAILED.equals(message.getStatus())) {
            throw new IllegalArgumentException("Only a failed message can be retried; " + message.getRef() + " is "
                    + message.getStatus() + ".");
        }
        String error = message.getLastError();
        message.retry();
        trail.changed(message, "Queued again by " + user.getUsername() + " after: " + error, MessageEvent.STAFF, null,
                user.getUsername());
        audit.record(user.getUsername(), "Message retried", "Message", message.getRef(), error);
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
        String before = preference == null ? "not set" : preference.getStatus();
        if (preference == null) {
            preference = new ChannelPreference(customer.getRef(), channel);
            preference.set(status, contact, verified, user.getUsername());
            em.persist(preference);
        } else {
            preference.set(status, contact, verified, user.getUsername());
        }
        audit.record(user.getUsername(), "Channel preference changed", "Customer", customer.getRef(),
                label(channel) + ": " + before + " -> " + status + (verified ? " (verified contact)" : ""));
        return preferencesOf(customer);
    }

    // ── event notifications ──────────────────────────────────────────────────────────────────────────

    /**
     * One message per approved template of the event type (AT-SH09B-01): customer templates go to the customer the
     * event names; intermediary templates go to the authorised agent servicing the policy, if there is one, and show
     * only references (MergeFields). Idempotent: the dedup key (event + recipient + purpose + template version) already
     * present means the event was handled. A recipient who cannot be messaged gets a SUPPRESSED message with the reason;
     * nothing is dropped silently. Each message records why its recipient was chosen (NS-SH09B-007).
     */
    @Transactional
    public int notifyFor(DomainEvent event) {
        List<MessageTemplate> templates = em.createQuery(
                        "select t from MessageTemplate t where t.eventType = :type and t.status = :approved"
                                + " order by t.code asc", MessageTemplate.class)
                .setParameter("type", event.eventType())
                .setParameter("approved", MessageTemplate.APPROVED)
                .getResultList();
        if (templates.isEmpty()) {
            return 0;
        }
        String purpose = MessagePurposes.of(event.eventType());
        int created = 0;
        Recipient customer = null;
        Recipient agent = null;
        for (MessageTemplate template : templates) {
            Recipient to;
            if (INTERMEDIARY.equals(template.getAudience())) {
                agent = agent != null ? agent : intermediaryFor(event);
                to = agent;
            } else {
                customer = customer != null ? customer : customerFor(event);
                to = customer;
            }
            if (to.key() == null) {
                continue; // an intermediary template on a policy no intermediary services: nobody to message
            }
            String dedupKey = event.eventId() + ":" + to.key() + ":" + purpose + ":" + template.getCode() + ":v"
                    + template.getVersion();
            // Messages created before the purpose joined the key carry the older key: a replay must not repeat them.
            String earlierKey = event.eventId() + ":" + to.key() + ":" + template.getCode() + ":v" + template.getVersion();
            Long existing = em.createQuery(
                            "select count(m) from CommunicationMessage m where m.dedupKey in :keys", Long.class)
                    .setParameter("keys", List.of(dedupKey, earlierKey)).getSingleResult();
            if (existing > 0) {
                continue;
            }
            CommunicationMessage message = new CommunicationMessage(dedupKey, event.eventId(), event.eventType(),
                    event.aggregateRef(), to.customer() == null ? null : to.customer().getRef(), to.name(),
                    template.getChannel(), "system");
            Recipient recipient = to;
            java.util.function.Function<String, String> values = field -> recipient.value(field, event, portalLink);
            message.content(template.getCode(), template.getVersion(),
                    MergeFields.render(template.getSubject(), values), MergeFields.render(template.getBody(), values));
            message.recipient(template.getAudience(), to.basis(), purpose);
            if (to.unavailable() != null) {
                message.suppress(to.contact(template.getChannel()), to.unavailable());
            } else if (to.customer() != null) {
                route(message, to.customer(), template.getChannel());
            } else {
                String contact = to.contact(template.getChannel());
                if (contact == null) {
                    message.suppress(null, "No " + (EMAIL.equals(template.getChannel()) ? "email address" : "mobile number")
                            + " on file for intermediary " + to.key() + ".");
                } else {
                    message.queue(contact);
                }
            }
            save(message);
            trail.requested(message, "system", event.correlationId());
            if (CommunicationMessage.SUPPRESSED.equals(message.getStatus())) {
                log.info("Message {} for event {} suppressed: {}", message.getRef(), event.eventId(),
                        message.getLastError());
            }
            created++;
        }
        return created;
    }

    /**
     * Who a message goes to: {@code key} (customer or intermediary reference, UNRESOLVED, or null for nobody), a
     * customer record to route by preference, or an intermediary's contacts; {@code unavailable} suppresses with a reason.
     */
    private record Recipient(String key, String name, CustomerAccount customer, String email, String mobile,
            String basis, String unavailable, String intermediaryName) {

        String contact(String channel) {
            return EMAIL.equals(channel) ? blankToNull(email) : blankToNull(mobile);
        }

        String value(String field, DomainEvent event, String portalLink) {
            if (intermediaryName != null) {
                // An intermediary never gets a field outside MergeFields' safe list, even if one slipped into a template.
                return switch (field) {
                    case MergeFields.INTERMEDIARY_REF -> key;
                    case MergeFields.INTERMEDIARY_NAME -> intermediaryName;
                    case MergeFields.PORTAL_LINK -> portalLink;
                    default -> MergeFields.known(event.eventType(), INTERMEDIARY).contains(field) ? event.text(field) : "";
                };
            }
            return switch (field) {
                case MergeFields.CUSTOMER_REF -> customer == null ? "" : customer.getRef();
                case MergeFields.PORTAL_LINK -> portalLink;
                default -> event.text(field);
            };
        }
    }

    /** The customer the event names: by reference, else by a unique name; ambiguity or no match suppresses. */
    private Recipient customerFor(DomainEvent event) {
        String name = event.text("customerName");
        String ref = event.text("customerRef");
        CustomerAccount customer = null;
        if (ref != null) {
            customer = em.createQuery("select c from CustomerAccount c where c.ref = :ref", CustomerAccount.class)
                    .setParameter("ref", ref).getResultStream().findFirst().orElse(null);
        }
        String basis = "Customer named on " + event.eventType() + " for " + event.aggregateRef();
        if (customer != null) {
            return new Recipient(customer.getRef(), customer.getName(), customer, null, null,
                    basis + " (customer reference " + customer.getRef() + ")", null, null);
        }
        List<CustomerAccount> named = name == null ? List.of() : em.createQuery(
                        "select c from CustomerAccount c where c.name = :name order by c.id asc", CustomerAccount.class)
                .setParameter("name", name).getResultList();
        if (named.size() == 1) {
            customer = named.get(0);
            return new Recipient(customer.getRef(), customer.getName(), customer, null, null,
                    basis + " (the only customer named " + name + ": " + customer.getRef() + ")", null, null);
        }
        String reason = named.isEmpty() ? "No customer record matches " + (name == null ? "the event" : name) + "."
                : named.size() + " customers are named " + name + "; the recipient is ambiguous, so nothing was sent.";
        return new Recipient("UNRESOLVED", name, null, null, null, basis + " (not resolved)", reason, null);
    }

    /**
     * The intermediary servicing the event's policy (AT-SH09B-01): the open policy assignment, else the one who sold
     * the application. Only an ACTIVE intermediary with a current appointment is messaged; any other is suppressed
     * with the reason. Null key when no intermediary services the policy.
     */
    private Recipient intermediaryFor(DomainEvent event) {
        String policyRef = event.text("policyRef");
        Recipient nobody = new Recipient(null, null, null, null, null, null, null, null);
        if (policyRef == null) {
            return nobody;
        }
        String basis = "Intermediary servicing policy " + policyRef + " (open assignment)";
        List<?> rows = em.createNativeQuery("select i.record_ref, i.name, i.status, i.appointment_expiry, i.email, i.mobile"
                        + " from dist_intermediary i join dist_policy_assignment a on a.intermediary_id = i.id"
                        + " where a.policy_ref = ?1 and a.ended_on is null")
                .setParameter(1, policyRef).getResultList();
        if (rows.isEmpty()) {
            basis = "Intermediary who sold the application for policy " + policyRef;
            rows = em.createNativeQuery("select i.record_ref, i.name, i.status, i.appointment_expiry, i.email, i.mobile"
                            + " from dist_intermediary i join ins_application ap on ap.intermediary_code = i.record_ref"
                            + " join ins_policy p on p.application_ref = ap.record_ref where p.record_ref = ?1")
                    .setParameter(1, policyRef).getResultList();
        }
        if (rows.isEmpty()) {
            return nobody;
        }
        Object[] row = (Object[]) rows.get(0);
        String ref = (String) row[0];
        String name = (String) row[1];
        String status = (String) row[2];
        java.time.LocalDate expiry = row[3] instanceof java.sql.Date d ? d.toLocalDate()
                : row[3] instanceof java.time.LocalDate l ? l : null;
        String unavailable = !"ACTIVE".equals(status) ? "Intermediary " + ref + " is " + status
                + ", so it is not authorised to receive policy messages."
                : expiry != null && expiry.isBefore(java.time.LocalDate.now())
                        ? "Intermediary " + ref + "'s appointment expired on " + expiry + "." : null;
        return new Recipient(ref, name, null, (String) row[4], (String) row[5], basis + ": " + ref, unavailable, name);
    }

    // ── provider status callbacks (Blueprint §5) ─────────────────────────────────────────────────────

    /**
     * Applies a provider status callback (already authenticated by its signature) to the message it names, keeping the
     * raw callback as delivery evidence. Unknown message ids and states that would move a message backwards are
     * ignored, so retried or out-of-order callbacks are harmless. Returns the status after the callback, or null.
     */
    @Transactional
    public String providerStatus(String providerMessageId, String status, String detail, String rawPayload) {
        String reported = upper(status);
        if (!Set.of(CommunicationMessage.DELIVERED, CommunicationMessage.READ, CommunicationMessage.FAILED)
                .contains(reported)) {
            throw new IllegalArgumentException("Status must be DELIVERED, READ or FAILED.");
        }
        CommunicationMessage message = em.createQuery(
                        "select m from CommunicationMessage m where m.providerMessageId = :id", CommunicationMessage.class)
                .setParameter("id", providerMessageId)
                .getResultStream().findFirst().orElse(null);
        if (message == null) {
            log.info("Status callback for unknown provider message {} ignored", providerMessageId);
            return null;
        }
        if (message.providerStatus(reported, detail)) {
            trail.changed(message, "Provider reported " + reported.toLowerCase(Locale.ROOT)
                    + (detail == null ? "" : ": " + detail), MessageEvent.PROVIDER, rawPayload, "provider");
        }
        return message.getStatus();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    /** When the customer last wrote to us on the channel, for the WhatsApp customer-service window. */
    java.time.LocalDateTime lastInbound(String customerRef, String channel) {
        return em.createQuery("select max(i.receivedAt) from InboundCorrespondence i where i.customerRef = :ref "
                        + "and i.channel = :channel", java.time.LocalDateTime.class)
                .setParameter("ref", customerRef)
                .setParameter("channel", channel)
                .getSingleResult();
    }

    /** Customers whose verified Email or WhatsApp contact is {@code sender}: one match routes a reply, more is ambiguous. */
    List<CustomerAccount> customersWithContact(String channel, String sender) {
        String wanted = EMAIL.equals(channel) ? sender.trim().toLowerCase(Locale.ROOT) : digits(sender);
        if (wanted.isEmpty()) {
            return List.of();
        }
        java.util.Map<String, CustomerAccount> found = new java.util.LinkedHashMap<>();
        for (ChannelPreference p : em.createQuery("select p from ChannelPreference p where p.channel = :channel",
                ChannelPreference.class).setParameter("channel", channel).getResultList()) {
            String contact = p.getContact() == null ? "" : p.getContact();
            boolean match = EMAIL.equals(channel) ? contact.trim().equalsIgnoreCase(wanted) : digits(contact).equals(wanted);
            // Only a verified contact links a message to a customer (NS-SH09B-006, AT-SH09B-03).
            if (match && "Y".equals(p.getVerified())) {
                em.createQuery("select c from CustomerAccount c where c.ref = :ref", CustomerAccount.class)
                        .setParameter("ref", p.getCustomerRef()).getResultStream().findFirst()
                        .ifPresent(c -> found.put(c.getRef(), c));
            }
        }
        // The registered email and phone were verified when the customer was onboarded.
        if (EMAIL.equals(channel)) {
            em.createQuery("select c from CustomerAccount c where lower(c.email) = :email", CustomerAccount.class)
                    .setParameter("email", wanted).getResultList().forEach(c -> found.put(c.getRef(), c));
        } else {
            for (CustomerAccount c : em.createQuery("select c from CustomerAccount c where c.phone is not null",
                    CustomerAccount.class).getResultList()) {
                if (digits(c.getPhone()).equals(wanted)) {
                    found.put(c.getRef(), c);
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    // ── delivery dashboard and conversations (SH09B workspace) ───────────────────────────────────────

    /** Per channel, the messages raised in the last {@code days} days by state, and per day raised/delivered/failed. */
    @Transactional(readOnly = true)
    public CommunicationViews.MetricsView metrics(Integer days) {
        user("READ_COMMUNICATION");
        int period = days == null ? 30 : days;
        if (period < 1 || period > 366) {
            throw new IllegalArgumentException("Choose a period of 1 to 366 days.");
        }
        java.time.LocalDate first = java.time.LocalDate.now().minusDays(period - 1L);
        List<CommunicationMessage> raised = em.createQuery("select m from CommunicationMessage m where m.createdAt >= :from",
                        CommunicationMessage.class)
                .setParameter("from", first.atStartOfDay()).getResultList();
        List<CommunicationViews.ChannelMetrics> channels = new ArrayList<>();
        for (String channel : List.of(EMAIL, WHATSAPP)) {
            List<CommunicationMessage> mine = raised.stream().filter(m -> channel.equals(m.getChannel())).toList();
            long delivered = count(mine, CommunicationMessage.DELIVERED);
            long read = count(mine, CommunicationMessage.READ);
            long failed = count(mine, CommunicationMessage.FAILED);
            long inFlight = count(mine, CommunicationMessage.SUBMITTED) + count(mine, CommunicationMessage.ACCEPTED);
            long handed = delivered + read + failed + inFlight;
            channels.add(new CommunicationViews.ChannelMetrics(channel, mine.size(),
                    count(mine, CommunicationMessage.QUEUED), inFlight, delivered + read, read, failed,
                    count(mine, CommunicationMessage.SUPPRESSED),
                    handed == 0 ? null : Math.round((delivered + read) * 1000.0 / handed) / 10.0));
        }
        List<CommunicationViews.DayMetrics> byDay = new ArrayList<>();
        for (java.time.LocalDate day = first; !day.isAfter(java.time.LocalDate.now()); day = day.plusDays(1)) {
            java.time.LocalDate d = day;
            List<CommunicationMessage> that = raised.stream()
                    .filter(m -> m.getCreatedAt().toLocalDate().equals(d)).toList();
            byDay.add(new CommunicationViews.DayMetrics(d.toString(), that.size(),
                    count(that, CommunicationMessage.DELIVERED) + count(that, CommunicationMessage.READ),
                    count(that, CommunicationMessage.FAILED)));
        }
        long unowned = em.createQuery("select count(m) from CommunicationMessage m where m.status = :failed"
                + " and m.owner is null", Long.class).setParameter("failed", CommunicationMessage.FAILED).getSingleResult();
        return new CommunicationViews.MetricsView(period, first.toString(), channels, byDay, unowned);
    }

    private static long count(List<CommunicationMessage> messages, String status) {
        return messages.stream().filter(m -> status.equals(m.getStatus())).count();
    }

    /**
     * A customer's conversation, oldest first: every message sent to them and every reply linked to them, with the
     * service case each belongs to (SH09B case conversation timeline).
     */
    @Transactional(readOnly = true)
    public List<CommunicationViews.ConversationItem> conversation(String customerRef) {
        user("READ_COMMUNICATION");
        CustomerAccount customer = customerByRef(customerRef);
        List<CommunicationViews.ConversationItem> items = new ArrayList<>();
        for (CommunicationMessage m : em.createQuery("select m from CommunicationMessage m where m.customerRef = :ref"
                + " and (m.recipientRole is null or m.recipientRole = :customer) order by m.id", CommunicationMessage.class)
                .setParameter("ref", customer.getRef()).setParameter("customer", CUSTOMER).getResultList()) {
            String caseRef = m.getBusinessRef() != null && m.getBusinessRef().startsWith("SR-") ? m.getBusinessRef() : "";
            items.add(new CommunicationViews.ConversationItem("OUT", CommunicationViews.time(m.getCreatedAt()),
                    m.getChannel(), m.getRef(), CommunicationViews.blank(m.getSubject()), m.getBody(), m.getStatus(),
                    caseRef, m.getCreatedBy()));
        }
        for (InboundCorrespondence i : em.createQuery("select i from InboundCorrespondence i where i.customerRef = :ref"
                + " order by i.id", InboundCorrespondence.class).setParameter("ref", customer.getRef()).getResultList()) {
            items.add(new CommunicationViews.ConversationItem("IN", CommunicationViews.time(i.getReceivedAt()),
                    i.getChannel(), "IN-" + i.getId(), "", i.getBody(), i.getStatus(),
                    CommunicationViews.blank(i.getCaseRef()), CommunicationViews.mask(i.getSender())));
        }
        items.sort(java.util.Comparator.comparing(CommunicationViews.ConversationItem::at));
        return items;
    }

    // ── bulk operational notices (Blueprint §5) ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CommunicationViews.BulkNoticeView> bulkNotices() {
        user("READ_COMMUNICATION");
        return em.createQuery("select b from BulkNotice b order by b.id desc", BulkNotice.class).setMaxResults(100)
                .getResultStream().map(this::bulkView).toList();
    }

    /** {templateId, policyStatus (default Active), product (optional), reason}: counted now, sent once approved. */
    @Transactional
    public CommunicationViews.BulkNoticeView requestBulkNotice(JsonNode body) {
        AppUser user = user("MANAGE_COMMUNICATION");
        if (body == null || !body.hasNonNull("templateId")) {
            throw new IllegalArgumentException("Choose an approved notice template.");
        }
        MessageTemplate template = noticeTemplate(body.get("templateId").asLong());
        String status = text(body, "policyStatus");
        status = status == null ? "Active" : status;
        String product = text(body, "product");
        String reason = text(body, "reason");
        if (reason == null || reason.length() < 10 || reason.length() > 1000) {
            throw new IllegalArgumentException("Give the reason for this notice (10 to 1000 characters).");
        }
        List<CustomerAccount> audience = audience(status, product);
        if (audience.isEmpty()) {
            throw new IllegalArgumentException("No customers hold " + (product == null ? "" : product + " ")
                    + "policies that are " + status + ".");
        }
        BulkNotice notice = new BulkNotice(template.getId(), product, status, reason, audience.size(),
                user.getUsername());
        em.persist(notice);
        em.flush();
        notice.numbered();
        return bulkView(notice);
    }

    /** Releases a notice requested by someone else: one message per customer, through the usual consent checks. */
    @Transactional
    public CommunicationViews.BulkNoticeView approveBulkNotice(String ref) {
        AppUser user = user("APPROVE_TEMPLATE");
        BulkNotice notice = pendingNotice(ref);
        if (user.getUsername().equalsIgnoreCase(notice.getRequestedBy())) {
            throw new NoAuthorizationException("You requested notice " + ref + "; someone else must approve it.");
        }
        MessageTemplate template = noticeTemplate(notice.getTemplateId());
        int queued = 0;
        int suppressed = 0;
        for (CustomerAccount customer : audience(notice.getPolicyStatus(), notice.getProduct())) {
            String dedupKey = "bulk:" + notice.getRef() + ":" + customer.getRef() + ":" + template.getChannel();
            Long existing = em.createQuery("select count(m) from CommunicationMessage m where m.dedupKey = :key",
                    Long.class).setParameter("key", dedupKey).getSingleResult();
            if (existing > 0) {
                continue;
            }
            CommunicationMessage message = new CommunicationMessage(dedupKey, null, Events.OPERATIONAL_NOTICE,
                    notice.getRef(), customer.getRef(), customer.getName(), template.getChannel(), user.getUsername());
            java.util.function.Function<String, String> values = field -> switch (field) {
                case MergeFields.CUSTOMER_REF -> customer.getRef();
                case MergeFields.PORTAL_LINK -> portalLink;
                case "customerName" -> customer.getName();
                default -> "";
            };
            message.content(template.getCode(), template.getVersion(),
                    MergeFields.render(template.getSubject(), values), MergeFields.render(template.getBody(), values));
            message.recipient(CUSTOMER, "Holds " + (notice.getProduct() == null ? "a" : "a " + notice.getProduct())
                    + " policy that is " + notice.getPolicyStatus() + ": bulk notice " + notice.getRef() + " requested by "
                    + notice.getRequestedBy() + ", approved by " + user.getUsername(), MessagePurposes.OPERATIONAL_NOTICE);
            route(message, customer, template.getChannel());
            save(message);
            trail.requested(message, user.getUsername(), null);
            if (CommunicationMessage.SUPPRESSED.equals(message.getStatus())) {
                suppressed++;
            } else {
                queued++;
            }
        }
        notice.sent(queued, suppressed, user.getUsername());
        audit.record(user.getUsername(), "Bulk notice approved", "Bulk notice", notice.getRef(),
                "Requested by " + notice.getRequestedBy() + ": " + notice.getReason() + "; " + queued + " queued, "
                        + suppressed + " suppressed");
        return bulkView(notice);
    }

    @Transactional
    public CommunicationViews.BulkNoticeView rejectBulkNotice(String ref) {
        AppUser user = user("APPROVE_TEMPLATE");
        BulkNotice notice = pendingNotice(ref);
        notice.rejected(user.getUsername());
        audit.record(user.getUsername(), "Bulk notice rejected", "Bulk notice", notice.getRef(),
                "Requested by " + notice.getRequestedBy() + ": " + notice.getReason());
        return bulkView(notice);
    }

    private BulkNotice pendingNotice(String ref) {
        BulkNotice notice = em.createQuery("select b from BulkNotice b where b.ref = :ref", BulkNotice.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Notice " + ref + " not found"));
        if (!BulkNotice.PENDING.equals(notice.getStatus())) {
            throw new com.nsure.common.exception.ConflictException("Notice " + ref + " is already "
                    + notice.getStatus() + ".");
        }
        return notice;
    }

    private MessageTemplate noticeTemplate(Long id) {
        MessageTemplate template = em.find(MessageTemplate.class, id);
        if (template == null || !MessageTemplate.APPROVED.equals(template.getStatus())
                || !Events.OPERATIONAL_NOTICE.equals(template.getEventType())
                || !CUSTOMER.equals(template.getAudience())) {
            throw new IllegalArgumentException("Choose an approved customer template written for OperationalNotice.");
        }
        return template;
    }

    private List<CustomerAccount> audience(String policyStatus, String product) {
        jakarta.persistence.TypedQuery<String> query = em.createQuery("select distinct p.customerRef from InsurancePolicy p"
                        + " where p.status = :status and p.customerRef is not null"
                        + (product == null ? "" : " and p.product = :product"), String.class)
                .setParameter("status", policyStatus);
        if (product != null) {
            query.setParameter("product", product);
        }
        List<String> refs = query.getResultList();
        if (refs.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select c from CustomerAccount c where c.ref in :refs order by c.ref", CustomerAccount.class)
                .setParameter("refs", refs).getResultList();
    }

    private CommunicationViews.BulkNoticeView bulkView(BulkNotice b) {
        MessageTemplate template = em.find(MessageTemplate.class, b.getTemplateId());
        return new CommunicationViews.BulkNoticeView(b.getRef(), b.getTemplateId(),
                template == null ? "" : template.getCode() + " v" + template.getVersion(), b.getProduct(),
                b.getPolicyStatus(), b.getReason(), b.getRecipients(), b.getStatus(), b.getRequestedBy(),
                CommunicationViews.time(b.getRequestedAt()), b.getDecidedBy(), CommunicationViews.time(b.getDecidedAt()),
                b.getQueued(), b.getSuppressed());
    }

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
