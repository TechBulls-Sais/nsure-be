package com.nsure.communication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * A controlled bulk operational notice (Solution Blueprint §5): an approved template sent to the customers holding
 * policies of a given status (and product), requested by one person and released only when another approves it.
 */
@Entity
@Table(name = "com_bulk_notice")
public class BulkNotice {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Assigned from the id right after insert (BN-00001), like message references.
    @Column(name = "record_ref", unique = true, length = 30)
    private String ref;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(length = 100)
    private String product;

    @Column(name = "policy_status", nullable = false, length = 40)
    private String policyStatus;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(nullable = false)
    private int recipients;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "decided_by", length = 100)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    private Integer queued;

    private Integer suppressed;

    protected BulkNotice() {
    }

    BulkNotice(Long templateId, String product, String policyStatus, String reason, int recipients,
            String requestedBy) {
        this.templateId = templateId;
        this.product = product;
        this.policyStatus = policyStatus;
        this.reason = reason;
        this.recipients = recipients;
        this.status = PENDING;
        this.requestedBy = requestedBy;
        this.requestedAt = LocalDateTime.now();
    }

    void numbered() {
        this.ref = String.format("BN-%05d", id);
    }

    void sent(int queued, int suppressed, String actor) {
        this.status = SENT;
        this.queued = queued;
        this.suppressed = suppressed;
        this.decidedBy = actor;
        this.decidedAt = LocalDateTime.now();
    }

    void rejected(String actor) {
        this.status = REJECTED;
        this.decidedBy = actor;
        this.decidedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public String getProduct() {
        return product;
    }

    public String getPolicyStatus() {
        return policyStatus;
    }

    public String getReason() {
        return reason;
    }

    public int getRecipients() {
        return recipients;
    }

    public String getStatus() {
        return status;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public Integer getQueued() {
        return queued;
    }

    public Integer getSuppressed() {
        return suppressed;
    }
}
