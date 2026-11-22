package com.nsure.communication;

import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.portfolio.CustomerAccount;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The delivery rules of SH09B, kept as configuration the communications administrator changes in the workspace:
 *
 * <ul>
 *   <li><b>Purpose rules</b> (AT-SH09B-02, NS-SH09B-005): per purpose, whether it is a contractual notice, which
 *       channels a message may fall back to when its own channel cannot reach the customer, whether the customer may
 *       opt out of the purpose itself, and whether it is marketing (opt-in with consent evidence only).</li>
 *   <li><b>The event-to-recipient matrix</b> (NS-SH09B-001): which recipient roles each business event messages.</li>
 *   <li><b>Routing</b>: whether a customer may be messaged on a channel for a purpose (purpose choice first, then the
 *       channel preference), and at which contact.</li>
 *   <li><b>Fallback</b>: a message that was suppressed or failed for good is followed by one on the next channel the
 *       purpose allows, only when the customer has not opted out of it and the contact is verified. Every decision,
 *       including "no fallback", is written to the message's trail; nothing is silently assumed.</li>
 * </ul>
 *
 * The seeded values are demo defaults (changeset 195), not an approved messaging policy.
 */
@Component
public class DeliveryRules {

    static final String SMS = "SMS";
    static final String ALL = "ALL";
    static final String LENDER = "LENDER";
    /** Channels a fallback may use: free text is allowed there, unlike WhatsApp outside its 24-hour window. */
    static final Set<String> FALLBACK_CHANNELS = Set.of(CommunicationService.EMAIL, SMS);
    static final List<String> ROLES = List.of(CommunicationService.CUSTOMER, CommunicationService.INTERMEDIARY, LENDER);
    private static final Set<String> LIVE = Set.of(CommunicationMessage.QUEUED, CommunicationMessage.SUBMITTED,
            CommunicationMessage.ACCEPTED, CommunicationMessage.DELIVERED, CommunicationMessage.READ);

    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final MessageTrail trail;
    private final EvidenceChain audit;

    public DeliveryRules(EntityManager em, JdbcTemplate jdbc, MessageTrail trail, EvidenceChain audit) {
        this.em = em;
        this.jdbc = jdbc;
        this.trail = trail;
        this.audit = audit;
    }

    // ── purpose rules ────────────────────────────────────────────────────────────────────────────

    /** One purpose's rule. */
    public record PurposeRule(String purpose, String label, boolean contractual, List<String> fallbackChannels,
            boolean customerMayOptOut, boolean marketing, String updatedBy, String updatedAt) {
    }

    List<PurposeRule> purposes() {
        return jdbc.query("select * from com_purpose_rule order by marketing, contractual desc, label", (rs, n) ->
                new PurposeRule(rs.getString("purpose"), rs.getString("label"), "Y".equals(rs.getString("contractual")),
                        channels(rs.getString("fallback_channels")), "Y".equals(rs.getString("customer_may_opt_out")),
                        "Y".equals(rs.getString("marketing")), rs.getString("updated_by"),
                        String.valueOf(rs.getTimestamp("updated_at").toLocalDateTime())));
    }

    PurposeRule purpose(String purpose) {
        if (purpose == null) {
            return null;
        }
        return purposes().stream().filter(p -> p.purpose().equals(purpose)).findFirst().orElse(null);
    }

    /** {fallbackChannels, customerMayOptOut}: changes a purpose rule; the change is audit evidence. */
    @Transactional(propagation = Propagation.MANDATORY)
    PurposeRule savePurpose(String purpose, List<String> fallback, Boolean mayOptOut, String reason, String actor) {
        PurposeRule rule = purpose(purpose);
        if (rule == null) {
            throw new com.nsure.common.exception.NotFoundException("Purpose " + purpose + " not found");
        }
        List<String> chosen = fallback == null ? rule.fallbackChannels() : fallback;
        for (String channel : chosen) {
            if (!FALLBACK_CHANNELS.contains(channel)) {
                throw new IllegalArgumentException("A fallback may use Email or SMS only: a WhatsApp message outside the"
                        + " customer's 24-hour window needs its own provider-approved template.");
            }
        }
        if (rule.marketing() && !chosen.isEmpty()) {
            throw new IllegalArgumentException("Marketing never falls back to another channel.");
        }
        boolean optOut = mayOptOut == null ? rule.customerMayOptOut() : mayOptOut;
        if (rule.marketing() && !optOut) {
            throw new IllegalArgumentException("A customer may always withdraw marketing consent.");
        }
        if (reason == null || reason.trim().length() < 5) {
            throw new IllegalArgumentException("Give the reason for changing the rule (at least 5 characters).");
        }
        jdbc.update("update com_purpose_rule set fallback_channels = ?, customer_may_opt_out = ?, updated_by = ?,"
                + " updated_at = ? where purpose = ?", String.join(",", chosen), optOut ? "Y" : "N", actor,
                LocalDateTime.now(), purpose);
        audit.record(actor, "Message purpose rule changed", "Message purpose", purpose, "Fallback "
                + (chosen.isEmpty() ? "none" : String.join(" then ", chosen)) + "; customer may opt out: "
                + (optOut ? "yes" : "no") + " · " + reason.trim());
        return purpose(purpose);
    }

    // ── the event-to-recipient matrix ─────────────────────────────────────────────────────────────

    /** One cell of the matrix. */
    public record RecipientRule(String eventType, String recipientRole, String purpose, boolean enabled, String note,
            String updatedBy, String updatedAt) {
    }

    List<RecipientRule> recipients() {
        return jdbc.query("select * from com_recipient_rule order by event_type, recipient_role", (rs, n) ->
                new RecipientRule(rs.getString("event_type"), rs.getString("recipient_role"), rs.getString("purpose"),
                        "Y".equals(rs.getString("enabled")), rs.getString("note"), rs.getString("updated_by"),
                        String.valueOf(rs.getTimestamp("updated_at").toLocalDateTime())));
    }

    RecipientRule recipient(String eventType, String role) {
        List<RecipientRule> rows = jdbc.query("select * from com_recipient_rule where event_type = ? and recipient_role = ?",
                (rs, n) -> new RecipientRule(rs.getString("event_type"), rs.getString("recipient_role"),
                        rs.getString("purpose"), "Y".equals(rs.getString("enabled")), rs.getString("note"),
                        rs.getString("updated_by"), String.valueOf(rs.getTimestamp("updated_at").toLocalDateTime())),
                eventType, role);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Whether an event messages a recipient role. Without a row the customer and the servicing intermediary are
     * messaged (as before the matrix existed); any other role only when the matrix says so.
     */
    boolean sends(String eventType, String role) {
        RecipientRule rule = recipient(eventType, role);
        if (rule == null) {
            return CommunicationService.CUSTOMER.equals(role) || CommunicationService.INTERMEDIARY.equals(role);
        }
        return rule.enabled();
    }

    /** The purpose a message for this event and role is sent for: the matrix's, else the event's default. */
    String purposeOf(String eventType, String role) {
        RecipientRule rule = recipient(eventType, role);
        return rule != null && rule.purpose() != null ? rule.purpose() : MessagePurposes.of(eventType);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    RecipientRule saveRecipient(String eventType, String role, boolean enabled, String reason, String actor) {
        if (eventType == null || !MergeFields.eventTypes().contains(eventType)) {
            throw new IllegalArgumentException("Choose one of the events messages are sent for: "
                    + String.join(", ", MergeFields.eventTypes()) + ".");
        }
        if (role == null || !ROLES.contains(role)) {
            throw new IllegalArgumentException("Recipient role must be one of " + String.join(", ", ROLES) + ".");
        }
        if (reason == null || reason.trim().length() < 5) {
            throw new IllegalArgumentException("Give the reason for changing the matrix (at least 5 characters).");
        }
        RecipientRule existing = recipient(eventType, role);
        if (existing == null) {
            jdbc.update("insert into com_recipient_rule (event_type, recipient_role, purpose, enabled, note, updated_by,"
                    + " updated_at) values (?, ?, ?, ?, ?, ?, ?)", eventType, role, MessagePurposes.of(eventType),
                    enabled ? "Y" : "N", reason.trim(), actor, LocalDateTime.now());
        } else {
            jdbc.update("update com_recipient_rule set enabled = ?, updated_by = ?, updated_at = ? where event_type = ?"
                    + " and recipient_role = ?", enabled ? "Y" : "N", actor, LocalDateTime.now(), eventType, role);
        }
        audit.record(actor, "Recipient matrix changed", "Recipient rule", eventType + " → " + role,
                (enabled ? "Send" : "Do not send") + " · " + reason.trim());
        return recipient(eventType, role);
    }

    // ── routing ──────────────────────────────────────────────────────────────────────────────────

    /** Where a message would go on a channel, or why it may not be sent there. */
    record Route(String contact, String reason) {
    }

    /** Channel-level routing: the customer's preference for the channel, or its default. */
    Route route(CustomerAccount customer, String channel) {
        ChannelPreference preference = preference(customer.getRef(), channel);
        String chosen = preference == null ? null : blank(preference.getContact());
        if (CommunicationService.EMAIL.equals(channel)) {
            String contact = chosen != null ? chosen : blank(customer.getEmail());
            if (preference != null && ChannelPreference.OPTED_OUT.equals(preference.getStatus())) {
                return new Route(contact, "Customer opted out of Email messages.");
            }
            if (contact == null) {
                return new Route(null, "No email address on file.");
            }
            return new Route(contact, null);
        }
        if (SMS.equals(channel)) {
            // Demo default: SMS goes to the mobile number verified at onboarding unless the customer opted out.
            String contact = chosen != null ? chosen : blank(customer.getPhone());
            if (preference != null && ChannelPreference.OPTED_OUT.equals(preference.getStatus())) {
                return new Route(contact, "Customer opted out of SMS messages.");
            }
            if (contact == null) {
                return new Route(null, "No mobile number on file.");
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

    /**
     * Routing for a purpose (AT-SH09B-02): a purpose-level opt-out (for this channel or every channel) suppresses the
     * message first; then the channel preference applies.
     */
    Route route(CustomerAccount customer, String channel, String purpose) {
        Route route = route(customer, channel);
        String out = purposeOptOut(customer.getRef(), purpose, channel);
        if (out != null) {
            return new Route(route.contact(), out);
        }
        return route;
    }

    /** The reason a purpose-level choice stops this channel, or null. */
    String purposeOptOut(String customerRef, String purpose, String channel) {
        if (purpose == null) {
            return null;
        }
        List<String[]> states = jdbc.query("select status, channel from com_purpose_preference where customer_ref = ?"
                + " and purpose = ? and channel in (?, ?) order by case when channel = ? then 0 else 1 end",
                (rs, n) -> new String[] {rs.getString("status"), rs.getString("channel")},
                customerRef, purpose, channel, ALL, channel);
        PurposeRule rule = purpose(purpose);
        if (rule != null && rule.marketing()) {
            // Marketing is opt-in: without a recorded opt-in with evidence it is never sent.
            return !states.isEmpty() && ChannelPreference.OPTED_IN.equals(states.get(0)[0]) ? null
                    : "Customer has not opted in to " + rule.label().toLowerCase(Locale.ROOT) + ".";
        }
        if (!states.isEmpty() && ChannelPreference.OPTED_OUT.equals(states.get(0)[0])) {
            String label = rule == null ? purpose : rule.label();
            return "Customer opted out of " + label.toLowerCase(Locale.ROOT) + " messages"
                    + (ALL.equals(states.get(0)[1]) ? "." : " by " + label(channel) + ".");
        }
        return null;
    }

    /** A customer's purpose-level choices. */
    public record PurposeChoice(String purpose, String channel, String status, String consentSource, String evidence,
            String updatedBy, String updatedAt) {
    }

    List<PurposeChoice> choices(String customerRef) {
        return jdbc.query("select * from com_purpose_preference where customer_ref = ? order by purpose, channel",
                (rs, n) -> new PurposeChoice(rs.getString("purpose"), rs.getString("channel"), rs.getString("status"),
                        rs.getString("consent_source"), rs.getString("consent_evidence"), rs.getString("updated_by"),
                        String.valueOf(rs.getTimestamp("updated_at").toLocalDateTime())), customerRef);
    }

    /**
     * Records a customer's choice for one purpose (NS-SH01-006, AT-SH09B-02). A contractual or other non-optional
     * notice cannot be stopped, only routed; marketing needs an opt-in with its consent source and evidence.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    PurposeChoice choose(CustomerAccount customer, String purpose, String channel, String status, String source,
            String evidence, String actor) {
        PurposeRule rule = purpose(purpose);
        if (rule == null) {
            throw new IllegalArgumentException("Unknown purpose " + purpose + ".");
        }
        String scope = channel == null ? ALL : channel;
        if (!ALL.equals(scope) && !Set.of(CommunicationService.EMAIL, CommunicationService.WHATSAPP, SMS)
                .contains(scope)) {
            throw new IllegalArgumentException("Channel must be EMAIL, WHATSAPP, SMS or ALL.");
        }
        if (!ChannelPreference.OPTED_IN.equals(status) && !ChannelPreference.OPTED_OUT.equals(status)) {
            throw new IllegalArgumentException("Status must be OPTED_IN or OPTED_OUT.");
        }
        if (ChannelPreference.OPTED_OUT.equals(status) && !rule.customerMayOptOut()) {
            throw new IllegalArgumentException(rule.label() + " messages are " + (rule.contractual() ? "contractual"
                    : "service") + " notices: the customer can choose the channel but not stop them.");
        }
        String src = blank(source);
        String ev = blank(evidence);
        if (rule.marketing() && ChannelPreference.OPTED_IN.equals(status)
                && (src == null || ev == null || ev.length() < 10)) {
            throw new IllegalArgumentException("Marketing consent needs its source (e.g. PORTAL, PHONE, SIGNED_FORM) and"
                    + " the evidence (at least 10 characters, e.g. the call reference and wording read).");
        }
        if (ev != null && ev.length() > 300) {
            throw new IllegalArgumentException("Keep the evidence to 300 characters.");
        }
        List<String> before = jdbc.queryForList("select status from com_purpose_preference where customer_ref = ? and"
                + " purpose = ? and channel = ?", String.class, customer.getRef(), purpose, scope);
        LocalDateTime now = LocalDateTime.now();
        if (before.isEmpty()) {
            jdbc.update("insert into com_purpose_preference (customer_ref, purpose, channel, status, consent_source,"
                    + " consent_evidence, updated_by, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?)", customer.getRef(),
                    purpose, scope, status, src, ev, actor, now);
        } else {
            jdbc.update("update com_purpose_preference set status = ?, consent_source = ?, consent_evidence = ?,"
                    + " updated_by = ?, updated_at = ? where customer_ref = ? and purpose = ? and channel = ?", status,
                    src, ev, actor, now, customer.getRef(), purpose, scope);
        }
        history(customer.getRef(), scope, purpose, before.isEmpty() ? null : before.get(0), status, null, null, src,
                ev, actor);
        audit.record(actor, "Purpose preference changed", "Customer", customer.getRef(), rule.label() + " on "
                + label(scope) + ": " + (before.isEmpty() ? "default" : before.get(0)) + " -> " + status
                + (src == null ? "" : " (consent source " + src + ")"));
        return choices(customer.getRef()).stream().filter(c -> c.purpose().equals(purpose) && c.channel().equals(scope))
                .findFirst().orElseThrow();
    }

    /** One line of a customer's preference and verification history (NS-SH01-005). */
    @Transactional(propagation = Propagation.MANDATORY)
    void history(String customerRef, String channel, String purpose, String before, String after, String contact,
            Boolean verified, String source, String evidence, String actor) {
        jdbc.update("insert into com_preference_history (customer_ref, channel, purpose, status_before, status_after,"
                + " contact_masked, verified, consent_source, evidence, actor, occurred_at) values (?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?)", customerRef, channel, purpose, before, after,
                contact == null ? null : CommunicationViews.mask(contact), verified == null ? null : verified ? "Y" : "N",
                source, evidence, actor, LocalDateTime.now());
    }

    List<Map<String, Object>> historyOf(String customerRef) {
        return jdbc.query("select * from com_preference_history where customer_ref = ? order by id desc", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channel", rs.getString("channel"));
            m.put("purpose", CommunicationViews.blank(rs.getString("purpose")));
            m.put("before", CommunicationViews.blank(rs.getString("status_before")));
            m.put("after", rs.getString("status_after"));
            m.put("contact", CommunicationViews.blank(rs.getString("contact_masked")));
            m.put("verified", CommunicationViews.blank(rs.getString("verified")));
            m.put("consentSource", CommunicationViews.blank(rs.getString("consent_source")));
            m.put("evidence", CommunicationViews.blank(rs.getString("evidence")));
            m.put("actor", rs.getString("actor"));
            m.put("at", String.valueOf(rs.getTimestamp("occurred_at").toLocalDateTime()));
            return m;
        }, customerRef);
    }

    // ── fallback (AT-SH09B-02, NS-SH09B-005) ─────────────────────────────────────────────────────

    /**
     * Called when a customer message from an event could not reach its recipient (suppressed, or failed for good).
     * If another message for the same event and purpose already reaches the customer, nothing more is needed. Otherwise
     * the next channel the purpose rule allows is tried: the customer must not have opted out of it and its contact
     * must be verified. The decision is written to the trail either way. Returns the fallback message, or null.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    CommunicationMessage fallback(CommunicationMessage m) {
        if (m.getEventId() == null || m.getCustomerRef() == null || m.getFallbackRef() != null
                || !CommunicationService.CUSTOMER.equals(m.getRecipientRole())) {
            return null;
        }
        List<CommunicationMessage> family = em.createQuery("select x from CommunicationMessage x where x.eventId = :event"
                        + " and x.customerRef = :customer and x.purpose = :purpose order by x.id", CommunicationMessage.class)
                .setParameter("event", m.getEventId())
                .setParameter("customer", m.getCustomerRef())
                .setParameter("purpose", m.getPurpose())
                .getResultList();
        for (CommunicationMessage other : family) {
            if (!other.getId().equals(m.getId()) && LIVE.contains(other.getStatus())) {
                trail.system(m, "No fallback needed: the customer is reached by " + label(other.getChannel()) + " ("
                        + other.getRef() + ")");
                return null;
            }
        }
        PurposeRule rule = purpose(m.getPurpose());
        if (rule == null || rule.fallbackChannels().isEmpty()) {
            trail.system(m, "No fallback: the rule for " + (rule == null ? m.getPurpose() : rule.label())
                    + " messages allows no other channel");
            return null;
        }
        CustomerAccount customer = em.createQuery("select c from CustomerAccount c where c.ref = :ref",
                        CustomerAccount.class)
                .setParameter("ref", m.getCustomerRef()).getResultStream().findFirst().orElse(null);
        if (customer == null) {
            return null;
        }
        Set<String> tried = new LinkedHashSet<>();
        family.forEach(x -> tried.add(x.getChannel()));
        List<String> refused = new ArrayList<>();
        for (String channel : rule.fallbackChannels()) {
            if (tried.contains(channel)) {
                continue;
            }
            Route route = route(customer, channel, m.getPurpose());
            if (route.reason() != null) {
                refused.add(label(channel) + ": " + route.reason());
                continue;
            }
            if (!verified(customer, channel, route.contact())) {
                refused.add(label(channel) + ": the contact on file is not verified");
                continue;
            }
            CommunicationMessage fallback = new CommunicationMessage(cut(m.getDedupKey() + ">" + channel, 200),
                    m.getEventId(), m.getEventType(), m.getBusinessRef(), m.getCustomerRef(), m.getRecipientName(),
                    channel, "system");
            String subject = CommunicationService.EMAIL.equals(channel)
                    ? (m.getSubject() != null ? m.getSubject() : "A message about " + m.getBusinessRef()) : null;
            fallback.content(m.getTemplateCode(), m.getTemplateVersion(), subject, smsSafe(channel, m.getBody()));
            String why = m.getLastError() == null ? m.getStatus() : m.getLastError();
            fallback.recipient(m.getRecipientRole(), "Fallback for " + m.getRef() + " (" + why + "), allowed by the "
                    + rule.label() + " rule", m.getPurpose());
            fallback.fallbackFor(m.getRef());
            fallback.queue(route.contact());
            em.persist(fallback);
            em.flush();
            fallback.numbered();
            m.fellBackTo(fallback.getRef());
            trail.requested(fallback, "system", null);
            trail.system(m, "Fallback to " + label(channel) + " (" + fallback.getRef() + ") allowed by the "
                    + rule.label() + " rule: consent and a verified contact are on file");
            audit.record("system", "Message fallback", "Message", m.getRef(), label(m.getChannel()) + " -> "
                    + label(channel) + " " + fallback.getRef() + " · purpose " + m.getPurpose() + " · " + why);
            return fallback;
        }
        trail.system(m, "No fallback possible: " + (refused.isEmpty() ? "every channel the " + rule.label()
                + " rule allows was already tried" : String.join("; ", refused)));
        return null;
    }

    /** Whether the contact a fallback would use was verified (registered at onboarding, or a verified preference). */
    private boolean verified(CustomerAccount customer, String channel, String contact) {
        if (contact == null) {
            return false;
        }
        ChannelPreference preference = preference(customer.getRef(), channel);
        if (preference != null && "Y".equals(preference.getVerified()) && contact.equals(preference.getContact())) {
            return true;
        }
        if (CommunicationService.EMAIL.equals(channel)) {
            return contact.equalsIgnoreCase(String.valueOf(customer.getEmail()));
        }
        return !CommunicationService.digits(contact).isEmpty()
                && CommunicationService.digits(contact).equals(CommunicationService.digits(customer.getPhone()));
    }

    private ChannelPreference preference(String customerRef, String channel) {
        return em.createQuery("select p from ChannelPreference p where p.customerRef = :ref and p.channel = :channel",
                        ChannelPreference.class)
                .setParameter("ref", customerRef)
                .setParameter("channel", channel)
                .getResultStream().findFirst().orElse(null);
    }

    /** An SMS carries at most 480 characters; a longer approved text keeps its start and the portal link. */
    static String smsSafe(String channel, String body) {
        if (!SMS.equals(channel) || body == null || body.length() <= 480) {
            return body;
        }
        int link = body.lastIndexOf("http");
        String tail = link > 0 ? " " + body.substring(link) : "";
        return body.substring(0, Math.max(0, 470 - tail.length())) + "…" + tail;
    }

    static List<String> channels(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT)).toList();
    }

    static String label(String channel) {
        return switch (channel == null ? "" : channel) {
            case CommunicationService.EMAIL -> "Email";
            case CommunicationService.WHATSAPP -> "WhatsApp";
            case SMS -> "SMS";
            case ALL -> "every channel";
            default -> channel;
        };
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
