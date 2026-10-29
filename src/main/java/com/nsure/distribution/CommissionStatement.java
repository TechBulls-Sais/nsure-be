package com.nsure.distribution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** One intermediary's net commission (accruals less clawbacks) for payment: Issued → Paid, never edited once paid. */
@Entity
@Table(name = "dist_statement")
public class CommissionStatement {

    public static final String ISSUED = "ISSUED";
    public static final String PAID = "PAID";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "intermediary_id", nullable = false)
    private Intermediary intermediary;

    @Column(name = "net_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "issued_by", nullable = false, length = 100)
    private String issuedBy;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "paid_by", length = 100)
    private String paidBy;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    protected CommissionStatement() {
    }

    CommissionStatement(Intermediary intermediary, BigDecimal netAmount, int lineCount, String actor) {
        this.ref = "TMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.intermediary = intermediary;
        this.netAmount = netAmount;
        this.lineCount = lineCount;
        this.status = ISSUED;
        this.issuedBy = actor;
        this.issuedAt = LocalDateTime.now();
    }

    void numbered() {
        this.ref = String.format("CST-%05d", id);
    }

    void paid(String actor) {
        this.status = PAID;
        this.paidBy = actor;
        this.paidAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public Intermediary getIntermediary() {
        return intermediary;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public int getLineCount() {
        return lineCount;
    }

    public String getStatus() {
        return status;
    }

    public String getIssuedBy() {
        return issuedBy;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public String getPaidBy() {
        return paidBy;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }
}
