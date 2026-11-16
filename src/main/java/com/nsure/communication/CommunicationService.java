package com.nsure.communication;

import com.fasterxml.jackson.databind.JsonNode;
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
            CommunicationMessage.SUBMITTED, CommunicationMessage.DELIVERED, CommunicationMessage.FAILED,
            CommunicationMessage.SUPPRESSED, CommunicationMessage.READ);
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
     * Checker step: another user approves, and the previously approved version of the code is retired. Nobody
     * approves a version they drafted, the administrator included (Nsure Life NS-UX-004).
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
        if (template.getCreatedBy().equalsIgnoreCase(user.getUsername())) {
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
        if (WHATSAPP.equals(channel)) {
            // Blueprint §5 / WhatsApp Business policy: a free-form message only within 24 hours of the customer's last
            // WhatsApp message; outside that window only approved templates are sent.
            java.time.LocalDateTime last = lastInbound(customer.getRef(), WHATSAPP);
            if (last == null || last.isBefore(java.time.LocalDateTime.now().minusHours(24))) {
                throw new IllegalArgumentException("A free-form WhatsApp message can only be sent within 24 hours of "
                        + customer.getName() + "'s last WhatsApp message. Use Email, or an approved template.");
            }
        }
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

    // ── provider status callbacks (Blueprint §5) ─────────────────────────────────────────────────────

    /**
     * Applies a provider status callback (already authenticated by its signature) to the message it names. Unknown
     * message ids and states that would move a message backwards are ignored, so retried or out-of-order callbacks
     * are harmless. Returns the message status after the callback, or null for an unknown message.
     */
    @Transactional
    public String providerStatus(String providerMessageId, String status, String detail) {
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
        message.providerStatus(reported, detail);
        return message.getStatus();
    }

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
            route(message, customer, template.getChannel());
            save(message);
            if (CommunicationMessage.SUPPRESSED.equals(message.getStatus())) {
                suppressed++;
            } else {
                queued++;
            }
        }
        notice.sent(queued, suppressed, user.getUsername());
        return bulkView(notice);
    }

    @Transactional
    public CommunicationViews.BulkNoticeView rejectBulkNotice(String ref) {
        AppUser user = user("APPROVE_TEMPLATE");
        BulkNotice notice = pendingNotice(ref);
        notice.rejected(user.getUsername());
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
