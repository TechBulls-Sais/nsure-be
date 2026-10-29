package com.nsure.communication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * One version of a message template (NS-SH09B-003). Drafted by one user, approved by another; approving a version
 * retires the previously approved version of the same code. Approved content is never edited: a change is a new
 * version. The body holds {@code {{field}}} merge fields filled from the event payload.
 */
@Entity
@Table(name = "com_template")
public class MessageTemplate {

    public static final String DRAFT = "DRAFT";
    public static final String APPROVED = "APPROVED";
    public static final String RETIRED = "RETIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(nullable = false, length = 20)
    private String channel;

    @Column(nullable = false, length = 20)
    private String audience;

    @Column(nullable = false)
    private int version;

    @Column(length = 200)
    private String subject;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    protected MessageTemplate() {
    }

    MessageTemplate(String code, String eventType, String channel, String audience, int version, String subject,
            String body, String createdBy) {
        this.code = code;
        this.eventType = eventType;
        this.channel = channel;
        this.audience = audience;
        this.version = version;
        this.subject = subject;
        this.body = body;
        this.status = DRAFT;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }

    void approve(String approver) {
        status = APPROVED;
        approvedBy = approver;
        approvedAt = LocalDateTime.now();
    }

    void retire() {
        status = RETIRED;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getEventType() {
        return eventType;
    }

    public String getChannel() {
        return channel;
    }

    public String getAudience() {
        return audience;
    }

    public int getVersion() {
        return version;
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

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }
}
