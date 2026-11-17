package com.nsure.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A long-running command accepted for background processing (spec §10 operation status): ACCEPTED → PROCESSING →
 * SUCCEEDED (with its result) or FAILED (with an actionable error). Readable by whoever asked for it and by holders
 * of {@code readPermission}.
 */
@Entity
@Table(name = "int_operation")
public class Operation {

    public static final String ACCEPTED = "ACCEPTED";
    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "operation_type", nullable = false, length = 60)
    private String type;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

    @Column(name = "read_permission", nullable = false, length = 60)
    private String readPermission;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "result_ref", length = 100)
    private String resultRef;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR)
    @Column(name = "result_summary")
    private String result;

    @Column(name = "error_message", length = 1000)
    private String error;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    protected Operation() {
    }

    Operation(String type, String requestedBy, String readPermission, String correlationId) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.status = ACCEPTED;
        this.requestedBy = requestedBy;
        this.readPermission = readPermission;
        this.correlationId = correlationId;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    void processing() {
        status = PROCESSING;
        updatedAt = LocalDateTime.now();
    }

    void succeeded(String resultRef, String result) {
        status = SUCCEEDED;
        this.resultRef = resultRef;
        this.result = result;
        updatedAt = LocalDateTime.now();
        finishedAt = updatedAt;
    }

    void failed(String error) {
        status = FAILED;
        this.error = error == null ? "The operation failed." : error.length() <= 1000 ? error : error.substring(0, 1000);
        updatedAt = LocalDateTime.now();
        finishedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getStatus() {
        return status;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public String getReadPermission() {
        return readPermission;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getResultRef() {
        return resultRef;
    }

    public String getResult() {
        return result;
    }

    public String getError() {
        return error;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }
}
