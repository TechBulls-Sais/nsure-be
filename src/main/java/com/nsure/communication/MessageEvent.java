package com.nsure.communication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * One step in a message's life (NS-SH09B-007: delivery evidence and user actions): queued or suppressed, submitted,
 * accepted, delivered, read, failed, retried, taken. A provider callback keeps its raw body as the delivery evidence.
 * Never updated.
 */
@Entity
@Table(name = "com_message_event")
public class MessageEvent {

    static final String SYSTEM = "SYSTEM";
    static final String PROVIDER = "PROVIDER";
    static final String STAFF = "STAFF";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 500)
    private String detail;

    // SYSTEM, PROVIDER or STAFF.
    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "raw_payload", length = 4000)
    private String rawPayload;

    @Column(nullable = false, length = 100)
    private String actor;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    protected MessageEvent() {
    }

    MessageEvent(Long messageId, String status, String detail, String source, String rawPayload, String actor) {
        this.messageId = messageId;
        this.status = status;
        this.detail = cut(detail, 500);
        this.source = source;
        this.rawPayload = cut(rawPayload, 4000);
        this.actor = cut(actor == null ? "system" : actor, 100);
        this.occurredAt = LocalDateTime.now();
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public Long getMessageId() {
        return messageId;
    }

    public String getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public String getSource() {
        return source;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public String getActor() {
        return actor;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }
}
