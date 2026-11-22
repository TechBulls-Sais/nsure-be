package com.nsure.distribution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One payment run across intermediaries (NS-SH06-006): approved statements with a positive net, a control total and a
 * bank file. PREPARED → RELEASED (statements paid, CommissionPaid) → SETTLED (the bank confirmed the file; simulated).
 */
@Entity
@Table(name = "dist_payment_batch")
public class CommissionPaymentBatch {

    public static final String PREPARED = "PREPARED";
    public static final String RELEASED = "RELEASED";
    public static final String SETTLED = "SETTLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "statement_count", nullable = false)
    private int statementCount;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(name = "bank_file_ref", length = 60)
    private String bankFileRef;

    @Column(name = "prepared_by", nullable = false, length = 100)
    private String preparedBy;

    @Column(name = "prepared_at", nullable = false)
    private LocalDateTime preparedAt;

    @Column(name = "released_by", length = 100)
    private String releasedBy;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    protected CommissionPaymentBatch() {
    }

    CommissionPaymentBatch(int count, BigDecimal total, String provider, String actor) {
        this.ref = "TMP-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.status = PREPARED;
        this.statementCount = count;
        this.totalAmount = total;
        this.provider = provider;
        this.preparedBy = actor;
        this.preparedAt = LocalDateTime.now();
    }

    void numbered() {
        this.ref = String.format("CPB-%05d", id);
        this.bankFileRef = "PAYFILE-" + ref;
    }

    void released(String actor) {
        this.status = RELEASED;
        this.releasedBy = actor;
        this.releasedAt = LocalDateTime.now();
    }

    void settled() {
        this.status = SETTLED;
        this.settledAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public String getStatus() {
        return status;
    }

    public int getStatementCount() {
        return statementCount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getProvider() {
        return provider;
    }

    public String getBankFileRef() {
        return bankFileRef;
    }

    public String getPreparedBy() {
        return preparedBy;
    }

    public LocalDateTime getPreparedAt() {
        return preparedAt;
    }

    public String getReleasedBy() {
        return releasedBy;
    }

    public LocalDateTime getReleasedAt() {
        return releasedAt;
    }

    public LocalDateTime getSettledAt() {
        return settledAt;
    }
}
