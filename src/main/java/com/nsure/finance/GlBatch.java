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

    // Blueprint §7: prepared → submitted → accepted or rejected → reconciled.
    public static final String PREPARED = "PREPARED";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";
    public static final String RECONCILED = "RECONCILED";

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

    @Column(name = "submitted_by", length = 100)
    private String submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "gl_debit_total", precision = 19, scale = 2)
    private BigDecimal glDebitTotal;

    @Column(name = "gl_credit_total", precision = 19, scale = 2)
    private BigDecimal glCreditTotal;

    @Column(name = "reconciled_by", length = 100)
    private String reconciledBy;

    @Column(name = "reconciled_at")
    private LocalDateTime reconciledAt;

    // NS-SH08-006: the periods whose journals the batch carries, and what happened on the last delivery to the GL.
    @Column(name = "period_codes", length = 200)
    private String periodCodes;

    @Column(name = "delivery_note", length = 500)
    private String deliveryNote;

    protected GlBatch() {
    }

    void periods(String codes) {
        this.periodCodes = codes == null || codes.length() <= 200 ? codes : codes.substring(0, 200);
    }

    void delivery(String note) {
        this.deliveryNote = note == null || note.length() <= 500 ? note : note.substring(0, 500);
    }

    /** The GL never received it (checked by batch id): it may be sent again. */
    void backToPrepared(String note) {
        this.status = PREPARED;
        delivery(note);
    }

    public String getPeriodCodes() {
        return periodCodes;
    }

    public String getDeliveryNote() {
        return deliveryNote;
    }

    GlBatch(String ref, int journalCount, int lineCount, BigDecimal debitTotal, BigDecimal creditTotal,
            String createdBy) {
        this.ref = ref;
        this.status = PREPARED;
        this.journalCount = journalCount;
        this.lineCount = lineCount;
        this.debitTotal = debitTotal;
        this.creditTotal = creditTotal;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }

    void submit(String actor) {
        this.status = SUBMITTED;
        this.submittedBy = actor;
        this.submittedAt = LocalDateTime.now();
    }

    void reconcile(BigDecimal glDebit, BigDecimal glCredit, String actor) {
        this.status = RECONCILED;
        this.glDebitTotal = glDebit;
        this.glCreditTotal = glCredit;
        this.reconciledBy = actor;
        this.reconciledAt = LocalDateTime.now();
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public String getReconciledBy() {
        return reconciledBy;
    }

    public LocalDateTime getReconciledAt() {
        return reconciledAt;
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
