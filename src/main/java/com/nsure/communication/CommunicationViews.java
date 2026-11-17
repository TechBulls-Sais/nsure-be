package com.nsure.communication;

import java.time.LocalDateTime;
import java.util.List;

/** Response shapes for the communications workspace. Contacts are always masked. */
public final class CommunicationViews {

    private CommunicationViews() {
    }

    public record TemplateView(
            Long id,
            String code,
            String eventType,
            String channel,
            String audience,
            int version,
            String subject,
            String body,
            String status,
            String createdBy,
            String createdAt,
            String approvedBy,
            String approvedAt,
            List<String> placeholders,
            List<String> knownFields,
            // D-22: a withdrawal asked for (pending while the status is still APPROVED) or confirmed (WITHDRAWN).
            String withdrawalReason,
            String withdrawalRequestedBy,
            String withdrawalRequestedAt,
            String withdrawnBy,
            String withdrawnAt,
            // Why messages from it are sent (MessagePurposes): always a service purpose, never promotional.
            String purpose) {

        static TemplateView of(MessageTemplate t) {
            return new TemplateView(t.getId(), t.getCode(), t.getEventType(), t.getChannel(), t.getAudience(),
                    t.getVersion(), blank(t.getSubject()), t.getBody(), t.getStatus(), t.getCreatedBy(),
                    time(t.getCreatedAt()), blank(t.getApprovedBy()), time(t.getApprovedAt()),
                    MergeFields.placeholders(t.getSubject(), t.getBody()),
                    MergeFields.known(t.getEventType(), t.getAudience()),
                    blank(t.getWithdrawalReason()), blank(t.getWithdrawalRequestedBy()),
                    time(t.getWithdrawalRequestedAt()), blank(t.getWithdrawnBy()), time(t.getWithdrawnAt()),
                    MessagePurposes.of(t.getEventType()));
        }
    }

    /** A template rendered with sample values before it is saved (template designer preview). */
    public record TemplatePreview(String subject, String body, List<String> placeholders, List<String> knownFields,
            List<String> unknownFields, String purpose) {
    }

    public record MessageView(
            Long id,
            String ref,
            String eventId,
            String eventType,
            String businessRef,
            String customerRef,
            String recipientName,
            String channel,
            String contact,
            String templateCode,
            Integer templateVersion,
            String subject,
            String body,
            String status,
            int attempts,
            String providerMessageId,
            String lastError,
            String createdBy,
            String createdAt,
            String updatedAt,
            String deliveredAt,
            String readAt,
            String acceptedAt,
            String purpose,
            String recipientRole,
            String recipientBasis,
            String owner,
            String ownedAt) {

        static MessageView of(CommunicationMessage m) {
            return new MessageView(m.getId(), m.getRef(), blank(m.getEventId()), m.getEventType(),
                    blank(m.getBusinessRef()), blank(m.getCustomerRef()), blank(m.getRecipientName()), m.getChannel(),
                    mask(m.getContact()), blank(m.getTemplateCode()), m.getTemplateVersion(), blank(m.getSubject()),
                    m.getBody(), m.getStatus(), m.getAttempts(), blank(m.getProviderMessageId()),
                    blank(m.getLastError()), m.getCreatedBy(), time(m.getCreatedAt()), time(m.getUpdatedAt()),
                    time(m.getDeliveredAt()), time(m.getReadAt()), time(m.getAcceptedAt()), blank(m.getPurpose()),
                    blank(m.getRecipientRole()), blank(m.getRecipientBasis()), blank(m.getOwner()),
                    time(m.getOwnedAt()));
        }
    }

    /** One step of a message's trail: state, what happened, who or what did it, and the provider's raw report. */
    public record MessageEventView(String status, String detail, String source, String rawPayload, String actor,
            String occurredAt) {
    }

    /** A message with its full trail (NS-SH09B-007). */
    public record MessageDetailView(MessageView message, List<MessageEventView> trail) {
    }

    /** One channel's messages raised in the period, by state, and the share delivered of those the provider took. */
    public record ChannelMetrics(String channel, long total, long queued, long inFlight, long delivered, long read,
            long failed, long suppressed, Double deliveryRate) {
    }

    /** One day's messages: raised, delivered and failed. */
    public record DayMetrics(String day, long raised, long delivered, long failed) {
    }

    public record MetricsView(int days, String from, List<ChannelMetrics> channels, List<DayMetrics> byDay,
            long failedUnowned) {
    }

    /** One entry of a customer's conversation: an outbound message or an inbound reply, oldest first. */
    public record ConversationItem(String direction, String at, String channel, String ref, String subject,
            String body, String status, String caseRef, String by) {
    }

    /**
     * One channel for one customer. {@code explicit} is false when no preference was recorded and the default
     * applies; {@code deliverable} says whether a customer message on this channel would be sent now.
     */
    public record PreferenceView(
            String channel,
            String status,
            boolean explicit,
            String contact,
            String verified,
            boolean deliverable,
            String note,
            String updatedBy,
            String updatedAt) {
    }

    public record CustomerPreferencesView(String customerRef, String customerName, List<PreferenceView> channels) {
    }

    /** Inbound correspondence (Blueprint §5): matched to a customer and linked to a case, or waiting in triage. */
    public record InboundView(Long id, String channel, String sender, String body, String receivedAt, String status,
            String customerRef, String caseRef, String note, String handledBy, String verificationStatus,
            String verificationMethod, String verificationNote, String taskOwner, String taskDueAt) {

        static InboundView of(InboundCorrespondence m) {
            return new InboundView(m.getId(), m.getChannel(), mask(m.getSender()), m.getBody(), time(m.getReceivedAt()),
                    m.getStatus(), blank(m.getCustomerRef()), blank(m.getCaseRef()), blank(m.getNote()),
                    blank(m.getHandledBy()), blank(m.getVerificationStatus()), blank(m.getVerificationMethod()),
                    blank(m.getVerificationNote()), blank(m.getTaskOwner()), time(m.getTaskDueAt()));
        }
    }

    /** The outcome of a provider callback: the message state after it, and the service case an inbound message joined. */
    public record CallbackResult(String status, String caseRef) {
    }

    /** An approved bulk operational notice (Blueprint §5): requested by one person, released by another. */
    public record BulkNoticeView(String ref, Long templateId, String templateCode, String product, String policyStatus,
            String reason, int recipients, String status, String requestedBy, String requestedAt, String decidedBy,
            String decidedAt, Integer queued, Integer suppressed) {
    }

    /** One dispatcher run: messages handed over and accepted, delivery reports applied, retries and failures. */
    public record DispatchView(int processed, int accepted, int delivered, int retrying, int failed) {
    }

    static String mask(String contact) {
        if (contact == null || contact.isBlank()) {
            return "";
        }
        int at = contact.indexOf('@');
        if (at > 0) {
            String local = contact.substring(0, at);
            return local.substring(0, Math.min(2, local.length())) + "***" + contact.substring(at);
        }
        StringBuilder out = new StringBuilder();
        int digits = (int) contact.chars().filter(Character::isDigit).count();
        int seen = 0;
        for (char ch : contact.toCharArray()) {
            if (Character.isDigit(ch)) {
                seen++;
                out.append(seen > digits - 3 ? ch : '*');
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    static String time(LocalDateTime value) {
        return value == null ? "" : value.toString();
    }

    static String blank(String value) {
        return value == null ? "" : value;
    }
}
