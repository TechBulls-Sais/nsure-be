package com.nsure.distribution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A premium that should have earned commission and did not (NS-SH06-003, SH06 exception queue): sold through an
 * intermediary but never assigned, no commission rule in force, or a product the intermediary's agreement does not
 * cover. Kept open until someone retries it (after fixing the cause) or resolves it with a note.
 */
@Entity
@Table(name = "dist_commission_exception")
public class CommissionException {

    public static final String OPEN = "OPEN";
    public static final String RESOLVED = "RESOLVED";
    public static final String NO_ASSIGNMENT = "NO_ASSIGNMENT";
    public static final String NO_RULE = "NO_RULE";
    public static final String NOT_IN_AGREEMENT = "NOT_IN_AGREEMENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(name = "policy_ref", nullable = false, length = 20)
    private String policyRef;

    @Column(length = 100)
    private String product;

    @Column(name = "intermediary_ref", length = 20)
    private String intermediaryRef;

    @Column(name = "source_event_id", nullable = false, unique = true, length = 80)
    private String sourceEventId;

    @Column(name = "premium_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal premiumAmount;

    @Column(nullable = false, length = 20)
    private String basis;

    @Column(name = "receipt_ref", length = 20)
    private String receiptRef;

    @Column(name = "premium_date", nullable = false)
    private LocalDate premiumDate;

    @Column(name = "reason_code", nullable = false, length = 30)
    private String reasonCode;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(length = 500)
    private String resolution;

    @Column(name = "commission_ref", length = 20)
    private String commissionRef;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected CommissionException() {
    }

    CommissionException(String policyRef, String product, String intermediaryRef, String sourceEventId,
            BigDecimal premiumAmount, String basis, String receiptRef, LocalDate premiumDate, String reasonCode,
            String reason) {
        this.ref = "TMP-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.policyRef = policyRef;
        this.product = product;
        this.intermediaryRef = intermediaryRef;
        this.sourceEventId = sourceEventId;
        this.premiumAmount = premiumAmount;
        this.basis = basis;
        this.receiptRef = receiptRef;
        this.premiumDate = premiumDate;
        this.reasonCode = reasonCode;
        this.reason = reason.length() <= 500 ? reason : reason.substring(0, 500);
        this.status = OPEN;
        this.createdAt = LocalDateTime.now();
    }

    void numbered() {
        this.ref = String.format("CEX-%05d", id);
    }

    void stillFailing(String reasonCode, String reason) {
        this.reasonCode = reasonCode;
        this.reason = reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    void resolve(String resolution, String commissionRef, String actor) {
        this.status = RESOLVED;
        this.resolution = resolution;
        this.commissionRef = commissionRef;
        this.resolvedBy = actor;
        this.resolvedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public String getProduct() {
        return product;
    }

    public String getIntermediaryRef() {
        return intermediaryRef;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public BigDecimal getPremiumAmount() {
        return premiumAmount;
    }

    public String getBasis() {
        return basis;
    }

    public String getReceiptRef() {
        return receiptRef;
    }

    public LocalDate getPremiumDate() {
        return premiumDate;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getReason() {
        return reason;
    }

    public String getStatus() {
        return status;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public String getResolution() {
        return resolution;
    }

    public String getCommissionRef() {
        return commissionRef;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
