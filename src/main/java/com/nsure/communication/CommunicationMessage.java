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
 * (event + recipient + template version) is unique, so a replayed event never sends twice (AT-SH09B-01). A message
 * that may not be sent is kept as SUPPRESSED with the reason (AT-SH09B-02).
 */
@Entity
@Table(name = "com_message")
public class CommunicationMessage {

    public static final String QUEUED = "QUEUED";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String DELIVERED = "DELIVERED";
    public static final String FAILED = "FAILED";
    public static final String SUPPRESSED = "SUPPRESSED";

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

    void delivered(String providerMessageId) {
        attempts++;
        status = DELIVERED;
        this.providerMessageId = providerMessageId;
        lastError = null;
        updatedAt = LocalDateTime.now();
    }

    /** A provider error: stays queued for the next run until maxAttempts, then lands in the failed queue. */
    void failedAttempt(String error, int maxAttempts) {
        attempts++;
        lastError = cut(error, 500);
        status = attempts >= maxAttempts ? FAILED : QUEUED;
        updatedAt = LocalDateTime.now();
    }

    void retry() {
        status = QUEUED;
        attempts = 0;
        updatedAt = LocalDateTime.now();
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
}
