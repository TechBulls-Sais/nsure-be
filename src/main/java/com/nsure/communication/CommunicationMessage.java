package com.nsure.communication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * One outgoing message with its rendered content snapshot and template version (NS-SH09B-007). The dedup key
 * (event + recipient + purpose + template version) is unique, so a replayed event never sends twice (AT-SH09B-01). A
 * message that may not be sent is kept as SUPPRESSED with the reason (AT-SH09B-02).
 *
 * <p>States (NS-SH09B-004): QUEUED, SUBMITTED (handed to the provider), ACCEPTED (the provider took it and gave an id;
 * not yet delivery), DELIVERED and READ (only from the provider's delivery report), FAILED, SUPPRESSED. Every change is
 * kept in the message's trail ({@link MessageEvent}) with the provider's evidence.
 */
@Entity
@Table(name = "com_message")
public class CommunicationMessage {

    public static final String QUEUED = "QUEUED";
    public static final String SUBMITTED = "SUBMITTED";
    /** The provider accepted the message and gave its id. Acceptance is not delivery (NS-SH09B-004). */
    public static final String ACCEPTED = "ACCEPTED";
    public static final String DELIVERED = "DELIVERED";
    public static final String FAILED = "FAILED";
    public static final String SUPPRESSED = "SUPPRESSED";
    // Blueprint §5: read is reported by the provider where available. It is not proof the customer accepted anything.
    public static final String READ = "READ";
    /** failure_kind: retried with backoff, or rejected for good. */
    public static final String TRANSIENT = "TRANSIENT";
    public static final String PERMANENT = "PERMANENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(name = "dedup_key", nullable = false, unique = true, length = 200)
    private String dedupKey;

    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "business_ref", length = 60)
    private String businessRef;

    @Column(name = "customer_ref", length = 20)
    private String customerRef;

    @Column(name = "recipient_name", length = 100)
    private String recipientName;

    @Column(nullable = false, length = 20)
    private String channel;

    @Column(length = 150)
    private String contact;

    @Column(name = "template_code", length = 40)
    private String templateCode;

    @Column(name = "template_version")
    private Integer templateVersion;

    @Column(length = 200)
    private String subject;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "provider_message_id", length = 60)
    private String providerMessageId;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    // Why the message exists, from MessagePurposes: every purpose today is a service notice, never promotional.
    @Column(name = "purpose", length = 40)
    private String purpose;

    // CUSTOMER or INTERMEDIARY.
    @Column(name = "recipient_role", length = 20)
    private String recipientRole;

    // Why this person was chosen (NS-SH09B-007), e.g. "Policyholder on PolicyIssued for POL-..." or the bulk notice.
    @Column(name = "recipient_basis", length = 300)
    private String recipientBasis;

    // The person working this message in the failed queue (SH09B workspace).
    @Column(name = "owner", length = 100)
    private String owner;

    @Column(name = "owned_at")
    private LocalDateTime ownedAt;

    // NS-SH09B-005: a transient error waits until this time (bounded backoff) before the next attempt.
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    // TRANSIENT (retried, then exhausted) or PERMANENT (straight to the failed queue).
    @Column(name = "failure_kind", length = 20)
    private String failureKind;

    // AT-SH09B-02: the message this one stands in for on another channel, and the fallback raised for this one.
    @Column(name = "fallback_of", length = 20)
    private String fallbackOf;

    @Column(name = "fallback_ref", length = 20)
    private String fallbackRef;

    protected CommunicationMessage() {
    }

    CommunicationMessage(String dedupKey, String eventId, String eventType, String businessRef, String customerRef,
            String recipientName, String channel, String createdBy) {
        this.ref = "TMP-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.dedupKey = dedupKey;
        this.eventId = eventId;
        this.eventType = eventType;
        this.businessRef = businessRef;
        this.customerRef = customerRef;
        this.recipientName = cut(recipientName, 100);
        this.channel = channel;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = createdAt;
    }

    void content(String templateCode, Integer templateVersion, String subject, String body) {
        this.templateCode = templateCode;
        this.templateVersion = templateVersion;
        this.subject = cut(subject, 200);
        this.body = cut(body == null ? "" : body, 2000);
    }

    /** Who the message is for and why (NS-SH09B-007), and its purpose. */
    void recipient(String role, String basis, String purpose) {
        this.recipientRole = role;
        this.recipientBasis = cut(basis, 300);
        this.purpose = purpose;
    }

    /** Takes or gives up ownership of a message in the failed queue. */
    void own(String owner) {
        this.owner = owner;
        this.ownedAt = owner == null ? null : LocalDateTime.now();
    }

    void queue(String contact) {
        this.contact = contact;
        this.status = QUEUED;
    }

    void suppress(String contact, String reason) {
        this.contact = contact;
        this.status = SUPPRESSED;
        this.lastError = cut(reason, 500);
    }

    void numbered() {
        this.ref = String.format("MSG-%06d", id);
    }

    /** Handed to the provider, which has not answered yet (Blueprint §5: submitted is not yet delivered). */
    void submitted() {
        attempts++;
        status = SUBMITTED;
        updatedAt = LocalDateTime.now();
    }

    /** The provider took the message and gave its id. It is not delivered until the provider reports delivery. */
    void accepted(String providerMessageId) {
        status = ACCEPTED;
        this.providerMessageId = providerMessageId;
        lastError = null;
        acceptedAt = LocalDateTime.now();
        updatedAt = acceptedAt;
    }

    /**
     * A provider status callback. States only move forward (submitted → accepted → delivered → read); a late or
     * repeated callback for an earlier state is ignored, so out-of-order delivery cannot undo a later state. Failure is
     * accepted only before delivery. Returns whether anything changed.
     */
    boolean providerStatus(String reported, String detail) {
        int current = rank(status);
        int next = rank(reported);
        if (FAILED.equals(reported)) {
            if (!SUBMITTED.equals(status) && !ACCEPTED.equals(status)) {
                return false;
            }
            status = FAILED;
            lastError = cut(detail == null ? "Provider reported the message as failed" : detail, 500);
            updatedAt = LocalDateTime.now();
            return true;
        }
        if (next <= current || current < rank(SUBMITTED)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (deliveredAt == null) {
            deliveredAt = now;
        }
        if (READ.equals(reported)) {
            readAt = now;
        }
        status = reported;
        updatedAt = now;
        return true;
    }

    private static int rank(String state) {
        return switch (state == null ? "" : state) {
            case QUEUED -> 0;
            case SUBMITTED -> 1;
            case ACCEPTED -> 2;
            case DELIVERED -> 3;
            case READ -> 4;
            default -> -1;
        };
    }

    public LocalDateTime getDeliveredAt() {
        return deliveredAt;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    /** A provider error on the attempt just submitted: queued again until maxAttempts, then the failed queue. */
    void failedAttempt(String error, int maxAttempts) {
        failedAttempt(error, maxAttempts, null);
    }

    /**
     * A transient provider error (NS-SH09B-005): queued again, not before {@code nextAttempt}, until maxAttempts; then
     * the failed queue.
     */
    void failedAttempt(String error, int maxAttempts, LocalDateTime nextAttempt) {
        lastError = cut(error, 500);
        failureKind = TRANSIENT;
        status = attempts >= maxAttempts ? FAILED : QUEUED;
        nextAttemptAt = FAILED.equals(status) ? null : nextAttempt;
        updatedAt = LocalDateTime.now();
    }

    /** A permanent provider error: no retry; the message goes straight to the failed queue. */
    void failedPermanently(String error) {
        lastError = cut(error, 500);
        failureKind = PERMANENT;
        status = FAILED;
        nextAttemptAt = null;
        updatedAt = LocalDateTime.now();
    }

    void retry() {
        status = QUEUED;
        attempts = 0;
        nextAttemptAt = null;
        updatedAt = LocalDateTime.now();
    }

    /** This message stands in for {@code original} on another channel (AT-SH09B-02). */
    void fallbackFor(String original) {
        this.fallbackOf = original;
    }

    /** The fallback message raised because this one could not reach its recipient. */
    void fellBackTo(String fallback) {
        this.fallbackRef = fallback;
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getBusinessRef() {
        return businessRef;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getChannel() {
        return channel;
    }

    public String getContact() {
        return contact;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public Integer getTemplateVersion() {
        return templateVersion;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public String getLastError() {
        return lastError;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getRecipientRole() {
        return recipientRole;
    }

    public String getRecipientBasis() {
        return recipientBasis;
    }

    public String getOwner() {
        return owner;
    }

    public LocalDateTime getOwnedAt() {
        return ownedAt;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getFailureKind() {
        return failureKind;
    }

    public String getFallbackOf() {
        return fallbackOf;
    }

    public String getFallbackRef() {
        return fallbackRef;
    }
}
