package com.nsure.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * One Idempotency-Key a caller used for one operation (NS-API-002): the hash of the request it came with and, once
 * the request succeeded, the answer it got. Unique per caller, operation and key.
 */
@Entity
@Table(name = "int_idempotency_record")
public class IdempotencyRecord {

    static final String IN_PROGRESS = "IN_PROGRESS";
    static final String COMPLETED = "COMPLETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String caller;

    @Column(nullable = false, length = 300)
    private String operation;

    @Column(name = "idem_key", nullable = false, length = 64)
    private String key;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_type", length = 100)
    private String responseType;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR)
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected IdempotencyRecord() {
    }

    IdempotencyRecord(String caller, String operation, String key, String requestHash, LocalDateTime expiresAt) {
        this.caller = caller;
        this.operation = operation;
        this.key = key;
        this.requestHash = requestHash;
        this.status = IN_PROGRESS;
        this.createdAt = LocalDateTime.now();
        this.expiresAt = expiresAt;
    }

    void completed(int status, String type, String body) {
        this.status = COMPLETED;
        this.responseStatus = status;
        this.responseType = type;
        this.responseBody = body;
        this.completedAt = LocalDateTime.now();
    }

    Long getId() {
        return id;
    }

    String getRequestHash() {
        return requestHash;
    }

    String getStatus() {
        return status;
    }

    Integer getResponseStatus() {
        return responseStatus;
    }

    String getResponseType() {
        return responseType;
    }

    String getResponseBody() {
        return responseBody;
    }

    LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
