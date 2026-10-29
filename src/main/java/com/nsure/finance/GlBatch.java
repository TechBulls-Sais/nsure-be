package com.nsure.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Journals exported to the corporate GL with control totals (NS-SH08-006). Submitted → Accepted, or Rejected,
 * which releases its journals for the next export. Acknowledging twice is harmless: the batch ref is the key.
 */
@Entity
@Table(name = "fin_gl_batch")
public class GlBatch {

    public static final String SUBMITTED = "SUBMITTED";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "journal_count", nullable = false)
    private int journalCount;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(name = "debit_total", nullable = false, precision = 15, scale = 2)
    private BigDecimal debitTotal;

    @Column(name = "credit_total", nullable = false, precision = 15, scale = 2)
    private BigDecimal creditTotal;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "gl_reference", length = 60)
    private String glReference;

    @Column(name = "acknowledged_by", length = 100)
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(length = 500)
    private String note;

    protected GlBatch() {
    }

    GlBatch(String ref, int journalCount, int lineCount, BigDecimal debitTotal, BigDecimal creditTotal,
            String createdBy) {
        this.ref = ref;
        this.status = SUBMITTED;
        this.journalCount = journalCount;
        this.lineCount = lineCount;
        this.debitTotal = debitTotal;
        this.creditTotal = creditTotal;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }

    void acknowledge(String status, String glReference, String note, String actor) {
        this.status = status;
        this.glReference = glReference;
        this.note = note;
        this.acknowledgedBy = actor;
        this.acknowledgedAt = LocalDateTime.now();
    }

    public String getRef() {
        return ref;
    }

    public String getStatus() {
        return status;
    }

    public int getJournalCount() {
        return journalCount;
    }

    public int getLineCount() {
        return lineCount;
    }

    public BigDecimal getDebitTotal() {
        return debitTotal;
    }

    public BigDecimal getCreditTotal() {
        return creditTotal;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getGlReference() {
        return glReference;
    }

    public String getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public LocalDateTime getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public String getNote() {
        return note;
    }
}
